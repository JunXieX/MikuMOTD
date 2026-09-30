package com.mikumc.motd.inject;

import com.mikumc.motd.MikuMOTDPlugin;
import com.mikumc.motd.ping.ResponseTemplate;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.charset.StandardCharsets;

/**
 * 状态查询的快速路径：挂在帧解码器之后的入站处理器，直接在字节层面
 * 完成「握手 → 状态请求 → 状态响应 → 心跳」的整套交互。
 *
 * <p>命中状态查询的连接完全不进入 Velocity 的会话处理（无包对象分配、
 * 无事件分发、无 JSON 序列化），响应为预渲染的零拷贝帧切片；
 * 登录/传输握手则原样字节透传给原生管线并自移除，代理行为不受影响。</p>
 *
 * <p>安全设计：握手与请求各字段均校验长度边界，畸形包直接断开（不产生对象分配）；
 * 严格模式下乱序 ping 序列一律拒绝，防止空 ping 滥用；
 * 任何异常只影响当前连接（fail-open 面最小化），系统性失败由注入阶段的探测兜底。</p>
 */
public final class StatusFastPath extends ChannelInboundHandlerAdapter {

    public static final String NAME = "mikumotd-fastpath";

    private static final int PACKET_STATUS_REQUEST = 0x00;
    private static final int PACKET_PING_REQUEST = 0x01;
    private static final int NEXT_STATUS_STATE = 0x01;
    private static final int MAX_HOST_LENGTH = 1024;

    private enum Phase {
        HANDSHAKE,
        REQUEST,
        PING
    }

    private final MikuMOTDPlugin plugin;
    private Phase phase = Phase.HANDSHAKE;
    private int protocol;
    private String hostKey;

    public StatusFastPath(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            // 旧版客户端的 ping 由更前方的专用解码器处理，原样透传
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            switch (this.phase) {
                case HANDSHAKE -> this.readHandshake(ctx, buf);
                case REQUEST -> this.readRequest(ctx, buf);
                case PING -> this.readPing(ctx, buf);
            }
        } catch (Exception e) {
            buf.release();
            this.plugin.logFastPathFault(ctx.channel().remoteAddress(), e);
            ctx.channel().close();
        }
    }

    private void readHandshake(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        buf.markReaderIndex();
        int protocol = readVarInt(buf);
        String host = readString(buf);
        if (buf.readableBytes() < 3) {
            throw new IndexOutOfBoundsException("handshake too short");
        }
        int port = buf.readUnsignedShort();
        int next = readVarInt(buf);

        if (next == NEXT_STATUS_STATE) {
            this.protocol = protocol;
            this.hostKey = cleanHost(host).toLowerCase(java.util.Locale.ROOT) + ":" + port;
            switchDecoders(ctx);
            this.phase = Phase.REQUEST;
            buf.release();
            this.plugin.onStatusHandshake(ctx.channel().remoteAddress(), protocol);
        } else {
            // 登录或传输意图：还原字节位置，交还原生握手流程并退出快速路径
            buf.resetReaderIndex();
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(buf);
        }
    }

    private void readRequest(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        int packetId = readVarInt(buf);
        if (packetId != PACKET_STATUS_REQUEST) {
            rejectImproper(ctx, buf, "状态阶段收到 0x" + Integer.toHexString(packetId));
            return;
        }
        buf.release();
        this.respondStatus(ctx);
        this.phase = Phase.PING;
    }

    private void readPing(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        int packetId = readVarInt(buf);
        if (packetId == PACKET_PING_REQUEST && buf.readableBytes() == 8) {
            long time = buf.readLong();
            if (this.plugin.directWrite()) {
                // 直写模式：自建含长度前缀的完整帧
                ByteBuf pong = ctx.alloc().directBuffer(10);
                pong.writeByte(9).writeByte(PACKET_PING_REQUEST).writeLong(time);
                this.writeResponse(ctx, pong);
            } else {
                // 管线模式：心跳包与请求包格式相同（仅包号不同，恰为 0x01），原样写回由帧编码器补前缀
                buf.resetReaderIndex();
                ctx.writeAndFlush(buf.retain(), ctx.voidPromise());
            }
            buf.release();
            ctx.channel().close();
        } else if (packetId == PACKET_STATUS_REQUEST && this.plugin.allowImproperPings()) {
            // 宽松模式：重复的状态请求也响应（开放空 ping 滥用面，不建议开启）
            buf.release();
            this.respondStatus(ctx);
        } else {
            rejectImproper(ctx, buf, "心跳阶段收到 0x" + Integer.toHexString(packetId));
        }
    }

    private void respondStatus(ChannelHandlerContext ctx) {
        ResponseTemplate template = this.plugin.selectResponse(this.protocol, this.hostKey);
        if (template == null) {
            // 配置重载的瞬间窗口，直接结束连接即可
            ctx.channel().close();
            return;
        }
        ByteBuf response = template.acquire(this.protocol, this.plugin.directWrite());
        if (response == null) {
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
            ctx.writeAndFlush(msg, ctx.voidPromise());
        }
    }

    private void rejectImproper(ChannelHandlerContext ctx, ByteBuf buf, String detail) {
        buf.release();
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

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int bits = 0;
        while (true) {
            byte current = buf.readByte();
            value |= (current & 0x7F) << bits;
            if ((current & 0x80) == 0) {
                return value;
            }
            bits += 7;
            if (bits > 35) {
                throw new IndexOutOfBoundsException("varint too big");
            }
        }
    }

    private static String readString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > MAX_HOST_LENGTH || length > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("bad string length " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String cleanHost(String host) {
        String cleaned = host;
        int terminator = cleaned.indexOf('\u0000');
        if (terminator > -1) {
            cleaned = cleaned.substring(0, terminator);
        }
        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}
