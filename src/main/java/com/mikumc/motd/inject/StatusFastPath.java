package com.mikumc.motd.inject;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;

/**
 * 状态查询的快速路径：挂在帧解码器之后的入站处理器，直接在字节层面
 * 完成「握手 → 状态请求 → 状态响应 → 心跳」的整套交互。
 *
 * <p>命中状态查询的连接完全不进入 Velocity 的会话处理（无包对象分配、
 * 无事件分发、无 JSON 序列化），响应为预渲染的零拷贝帧切片；
 * 登录/传输握手则原样字节透传给原生管线并自移除，代理行为不受影响。</p>
 *
 * <p>安全设计：握手与请求各字段均校验长度边界与精确载荷长度（与原生解码器一致），
 * 畸形包直接断开且不产生对象分配；严格模式下乱序 ping 序列一律拒绝，防止空 ping 滥用；
 * 任何异常只影响当前连接（fail-open 面最小化），系统性失败由注入阶段兜底。</p>
 *
 * <p>缓冲所有权：入站帧解码器以 {@code readRetainedSlice} 交付，引用计数由本处理器承担。
 * 除「交还原生管线」外，所有路径都在 {@link #channelRead} 的 finally 中恰好释放一次；
 * 交接分支在调用 {@code fireChannelRead} 前就置位所有权标记，
 * 因此下游抛异常时也不会被重复释放。</p>
 */
public final class StatusFastPath extends ChannelInboundHandlerAdapter {

    public static final String NAME = "mikumotd-fastpath";

    private static final int PACKET_STATUS_REQUEST = 0x00;
    private static final int PACKET_PING_REQUEST = 0x01;
    private static final int NEXT_STATUS_STATE = 0x01;
    /** 握手主机名长度上限（防滥用边界，内容不参与任何逻辑）。 */
    private static final int MAX_HOST_LENGTH = 1024;
    /** Minecraft VarInt 的最长编码字节数。 */
    private static final int MAX_VARINT_BYTES = 5;

    /** 入站包的处置结论：交还原生管线，或由快速路径自行消费。 */
    private enum Verdict {
        HANDOFF,
        CONSUMED
    }

    private final MikuMOTDPlugin plugin;
    private Phase phase = Phase.HANDSHAKE;
    private int protocol;

    private enum Phase {
        HANDSHAKE,
        REQUEST,
        PING
    }

    public StatusFastPath(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            // 旧版客户端的 ping 由更前方的专用解码器处理，原样透传
            ctx.fireChannelRead(msg);
            return;
        }

        // true 表示缓冲所有权已转交给原生管线，此时本处理器不再释放
        boolean handedOff = false;
        try {
            if (this.phase == Phase.HANDSHAKE) {
                if (this.readHandshake(ctx, buf) == Verdict.HANDOFF) {
                    // 先置位再交接：即便下游抛异常，所有权也已归属下游（其 finally 负责释放）
                    handedOff = true;
                    ctx.fireChannelRead(buf);
                }
                return;
            }
            if (this.phase == Phase.REQUEST) {
                this.readRequest(ctx, buf);
            } else {
                this.readPing(ctx, buf);
            }
        } catch (Exception e) {
            this.plugin.logFastPathFault(ctx.channel().remoteAddress(), e);
            ctx.channel().close();
        } finally {
            if (!handedOff && buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    /**
     * 解析握手包，仅做判定与状态切换，不改动缓冲所有权。
     *
     * @return {@link Verdict#HANDOFF} 表示登录/传输意图，需由调用方交还原生管线
     */
    private Verdict readHandshake(ChannelHandlerContext ctx, ByteBuf buf) {
        buf.markReaderIndex();
        int packetId = readVarInt(buf);
        if (packetId != PACKET_STATUS_REQUEST) {
            throw new IndexOutOfBoundsException("握手包号异常 0x" + Integer.toHexString(packetId));
        }
        int protocol = readVarInt(buf);
        skipString(buf);
        if (buf.readableBytes() < 3) {
            throw new IndexOutOfBoundsException("handshake too short");
        }
        buf.readUnsignedShort();
        int next = readVarInt(buf);

        if (next == NEXT_STATUS_STATE) {
            this.protocol = protocol;
            switchDecoders(ctx);
            this.phase = Phase.REQUEST;
            this.plugin.onStatusHandshake(ctx.channel().remoteAddress(), protocol);
            return Verdict.CONSUMED;
        }

        // 登录或传输意图：还原字节位置，退出快速路径，交还原生握手流程
        buf.resetReaderIndex();
        ctx.pipeline().remove(this);
        return Verdict.HANDOFF;
    }

    private void readRequest(ChannelHandlerContext ctx, ByteBuf buf) {
        int packetId = readVarInt(buf);
        if (packetId != PACKET_STATUS_REQUEST) {
            rejectImproper(ctx, "状态阶段收到 0x" + Integer.toHexString(packetId));
            return;
        }
        if (buf.isReadable()) {
            // 与原生解码器一致：状态请求包必须为空载荷
            rejectImproper(ctx, "状态请求携带多余载荷 " + buf.readableBytes() + " 字节");
            return;
        }
        this.respondStatus(ctx);
        this.phase = Phase.PING;
    }

    private void readPing(ChannelHandlerContext ctx, ByteBuf buf) {
        int packetId = readVarInt(buf);
        if (packetId == PACKET_PING_REQUEST) {
            // 与原生解码器一致：心跳载荷必须恰好 8 字节
            if (buf.readableBytes() != Long.BYTES) {
                rejectImproper(ctx, "心跳载荷长度异常 " + buf.readableBytes());
                return;
            }
            long time = buf.readLong();
            // 自建含长度前缀的完整帧（本处理器位于 pipeline 前段，outbound 不经过帧编码器）
            ByteBuf pong = ctx.alloc().directBuffer(10);
            pong.writeByte(9).writeByte(PACKET_PING_REQUEST).writeLong(time);
            this.writeResponse(ctx, pong);
            ctx.channel().close();
        } else if (packetId == PACKET_STATUS_REQUEST && this.plugin.allowImproperPings()) {
            // 宽松模式：重复的状态请求也响应（开放空 ping 滥用面，不建议开启）
            this.respondStatus(ctx);
        } else {
            rejectImproper(ctx, "心跳阶段收到 0x" + Integer.toHexString(packetId));
        }
    }

    private void respondStatus(ChannelHandlerContext ctx) {
        ByteBuf response = this.plugin.selectResponse(this.protocol);
        if (response == null) {
            // 插件未就绪或配置重载的瞬间窗口，直接结束连接即可
            ctx.channel().close();
            return;
        }
        this.writeResponse(ctx, response);
    }

    private void writeResponse(ChannelHandlerContext ctx, ByteBuf msg) {
        if (this.plugin.directWrite()) {
            ChannelOutboundBuffer outbound = ctx.channel().unsafe().outboundBuffer();
            if (outbound == null) {
                msg.release();
                return;
            }
            outbound.addMessage(msg, msg.readableBytes(), ctx.voidPromise());
            ctx.channel().flush();
        } else {
            // 走标准写出路径（本处理器向 head 方向传播，帧编码器不在路径上，帧必须自带长度前缀）
            ctx.writeAndFlush(msg, ctx.voidPromise());
        }
    }

    private void rejectImproper(ChannelHandlerContext ctx, String detail) {
        this.plugin.logImproperPing(ctx.channel().remoteAddress(), detail);
        ctx.channel().close();
    }

    /** 把协议解码器同步到状态阶段，保证任何漏过快速路径的包仍能被原生管线正确解码。 */
    private static void switchDecoders(ChannelHandlerContext ctx) {
        MinecraftDecoder decoder = ctx.pipeline().get(MinecraftDecoder.class);
        if (decoder != null) {
            decoder.setState(StateRegistry.STATUS);
        }
        MinecraftVarintFrameDecoder frameDecoder = ctx.pipeline().get(MinecraftVarintFrameDecoder.class);
        if (frameDecoder != null) {
            frameDecoder.setState(StateRegistry.STATUS);
        }
    }

    /** 读取 Minecraft VarInt：最多 5 字节，超出即判为畸形包。 */
    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            byte current = buf.readByte();
            value |= (current & 0x7F) << (i * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IndexOutOfBoundsException("varint too big");
    }

    /** 跳过握手包内的主机名字符串：内容不参与任何逻辑，仅校验边界（零分配）。 */
    private static void skipString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > MAX_HOST_LENGTH || length > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("bad string length " + length);
        }
        buf.skipBytes(length);
    }
}
