package com.mikumc.motd.inject;

import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * 状态查询的快速路径：挂在帧解码器之后的入站处理器，直接在字节层面完成
 * 「握手 → 状态请求 → 状态响应 → 心跳」的整套交互。
 *
 * <p>命中状态查询的连接完全不进入 Velocity 的会话处理（无包对象分配、无事件分发、
 * 无 JSON 序列化），响应为预渲染的零拷贝帧切片；登录/传输握手则原样字节透传给原生管线并自移除。</p>
 *
 * <p>校验口径与原生解码器一致：状态请求必须空载、心跳必须恰好 8 字节、握手主机串只按长度跳过
 * （内容不参与任何逻辑）。畸形包直接断开。</p>
 *
 * <p>缓冲所有权：入站帧解码器以 {@code readRetainedSlice} 交付，引用计数由本处理器承担。
 * 除「交还原生管线」外，所有路径都在 {@link #channelRead} 的 finally 中恰好释放一次；
 * 交接分支在调用 {@code fireChannelRead} 前就置位所有权标记，因此下游抛异常也不会被重复释放。</p>
 */
public final class StatusFastPath extends ChannelInboundHandlerAdapter {

    public static final String NAME = "mikumotd-fastpath";

    private static final int PACKET_STATUS_REQUEST = 0x00;
    private static final int PACKET_PING_REQUEST = 0x01;
    private static final int NEXT_STATUS_STATE = 0x01;
    /** Minecraft VarInt 的最长编码字节数。 */
    private static final int MAX_VARINT_BYTES = 5;

    private final FastPathHost host;
    private Phase phase = Phase.HANDSHAKE;
    private int protocol;

    /** 入站包的处置结论：交还原生管线，或由快速路径自行消费。 */
    private enum Verdict {
        HANDOFF,
        CONSUMED
    }

    private enum Phase {
        HANDSHAKE,
        REQUEST,
        PING
    }

    public StatusFastPath(FastPathHost host) {
        this.host = host;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            // 旧版客户端的 ping 包与 HAProxy 头由其它处理器产出，原样透传
            ctx.fireChannelRead(msg);
            return;
        }

        // true 表示缓冲所有权已转交给原生管线，此时本处理器不再释放
        boolean handedOff = false;
        try {
            if (this.phase == Phase.HANDSHAKE) {
                if (this.readHandshake(ctx, buf) == Verdict.HANDOFF) {
                    // 先置位再交接：即便下游抛异常，所有权也已归属下游
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
        } catch (IndexOutOfBoundsException e) {
            // 解析越界只可能是畸形包（外部输入），按「非标准 ping」处理：
            // 是否记录由 log-improper-pings 决定，不打内部故障堆栈
            this.rejectImproper(ctx, e.getMessage());
        } catch (Exception e) {
            this.host.logFastPathFault(ctx.channel().remoteAddress(), e);
            ctx.channel().close();
        } finally {
            if (!handedOff) {
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
        if (buf.isReadable()) {
            // 原生 MinecraftDecoder 在 decode 之后同样会因尾随字节断开
            throw new IndexOutOfBoundsException("握手携带多余载荷 " + buf.readableBytes() + " 字节");
        }

        if (next == NEXT_STATUS_STATE) {
            this.protocol = protocol;
            markStatusState(ctx);
            this.phase = Phase.REQUEST;
            this.host.onStatusHandshake(ctx.channel().remoteAddress(), protocol);
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
            // 原生解码器对状态请求的 decodeExpectedMaxLength 为 0，带载荷同样会被断开
            rejectImproper(ctx, "状态请求携带多余载荷 " + buf.readableBytes() + " 字节");
            return;
        }
        this.respondStatus(ctx);
        this.phase = Phase.PING;
    }

    private void readPing(ChannelHandlerContext ctx, ByteBuf buf) {
        int packetId = readVarInt(buf);
        if (packetId == PACKET_PING_REQUEST) {
            // 原生解码器要求心跳载荷恰好 8 字节
            if (buf.readableBytes() != Long.BYTES) {
                rejectImproper(ctx, "心跳载荷长度异常 " + buf.readableBytes());
                return;
            }
            long time = buf.readLong();
            ByteBuf pong = ctx.alloc().directBuffer(10);
            pong.writeByte(9).writeByte(PACKET_PING_REQUEST).writeLong(time);
            // 关闭动作挂在写完成的 promise 上：先 flush 再 close 会把尚未写出的 pong 丢弃
            this.writeResponse(ctx, pong, true);
        } else if (packetId == PACKET_STATUS_REQUEST && this.host.allowImproperPings()) {
            // 宽松模式：重复的状态请求也响应（开放空 ping 滥用面，不建议开启）
            this.respondStatus(ctx);
        } else {
            rejectImproper(ctx, "心跳阶段收到 0x" + Integer.toHexString(packetId));
        }
    }

    private void respondStatus(ChannelHandlerContext ctx) {
        ByteBuf response = this.host.selectResponse(this.protocol);
        if (response == null) {
            // 插件未就绪或正在换代的瞬间：直接结束连接即可
            ctx.channel().close();
            return;
        }
        this.writeResponse(ctx, response, false);
    }

    /**
     * 写出完整帧（自带长度前缀）。本处理器位于帧编码器之前，出站方向不经过任何编码器，
     * 因此写的是整帧而不是裸包。
     */
    private void writeResponse(ChannelHandlerContext ctx, ByteBuf msg, boolean closeAfterWrite) {
        // 关闭动作挂在写完成的 promise 上：写失败时同样会关闭（由出站缓冲释放消息）
        ChannelPromise promise = closeAfterWrite
                ? ctx.channel().newPromise().addListener(ChannelFutureListener.CLOSE)
                : ctx.voidPromise();
        ctx.writeAndFlush(msg, promise);
    }

    private void rejectImproper(ChannelHandlerContext ctx, String detail) {
        this.host.logImproperPing(ctx.channel().remoteAddress(), detail);
        ctx.channel().close();
    }

    /**
     * 把帧解码器同步到状态阶段：它在 HANDSHAKE 状态下会按握手包校验 serverbound 帧，
     * 不同步会把随后的状态请求判成畸形握手。原生包解码器与编码器无需同步——本路径不再产生原生包。
     */
    private static void markStatusState(ChannelHandlerContext ctx) {
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

    /** 跳过握手包内的主机名字符串：内容不参与任何逻辑，仅校验长度边界（零分配）。 */
    private static void skipString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("bad string length " + length);
        }
        buf.skipBytes(length);
    }
}
