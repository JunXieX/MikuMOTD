package com.mikumc.motd.ping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mikumc.motd.inject.FastPathHost;
import com.mikumc.motd.inject.Injector;
import com.mikumc.motd.inject.StatusFastPath;
import com.mikumc.motd.ping.ResponseTemplate.SampleEntry;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 端到端协议一致性测试：用 Velocity 真实的帧解码器 + 真实的状态阶段解码器
 * 组成与线上一致的入站管线，把客户端的真实字节喂进去，检查快速路径的
 * 分包、状态切换、响应字节与连接关闭行为。
 *
 * <p>这类问题（长度前缀、状态未切换、缓冲所有权、半包）在纯函数测试里看不出来。</p>
 */
class ProtocolConformanceTest {

    private static final int PROTOCOL = 773;

    /** 预渲染帧必须能被真实帧解码器完整切分，且包体与模板逐字节一致。 */
    @Test
    void preRenderedFrameIsAcceptedByRealFrameDecoder() {
        ResponseTemplate template = sampleTemplate();
        EmbeddedChannel channel = frameChannel();
        try {
            ByteBuf frame = template.acquire(PROTOCOL);
            assertNotNull(frame);
            byte[] raw = drain(frame);

            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(raw)));
            ByteBuf decoded = channel.readInbound();
            assertNotNull(decoded, "真实解码器应切出一个完整包");
            try {
                byte[] body = drain(decoded);
                // 解码器去掉的是帧长度前缀，剩余应为 [包号][JSON 长度][JSON]
                assertEquals(0x00, body[0], "状态响应包号");
                assertEquals(raw.length - prefixLength(raw), body.length, "包体长度应与帧前缀一致");
            } finally {
                decoded.release();
            }
            assertNull(channel.readInbound(), "不应残留半包或多包");
        } finally {
            channel.finishAndReleaseAll();
            template.dispose();
        }
    }

    /** 握手 + 状态请求在同一段内到达：快速路径应回出模板帧，且不惊动下游解码器。 */
    @Test
    void fastPathAnswersStatusRequestInSameSegmentAsHandshake() {
        ResponseTemplate template = sampleTemplate();
        try {
            for (boolean directWrite : new boolean[]{true, false}) {
                FastPathHostStub host = new FastPathHostStub(template, directWrite);
                EmbeddedChannel channel = fastPathChannel(host);
                try {
                    byte[] handshake = handshake(PROTOCOL, "mc.example.com", 25565, 1);
                    byte[] request = frame(new byte[]{0x00});
                    // 快速路径自行消费入站包，故 writeInbound 无入站产出（返回 false）
                    channel.writeInbound(Unpooled.wrappedBuffer(concat(handshake, request)));

                    ByteBuf out = channel.readOutbound();
                    assertNotNull(out, "应写出状态响应（directWrite=" + directWrite + "）");
                    byte[] actual = drain(out);
                    assertEquals(PROTOCOL, host.lastHandshakeProtocol.get(), "会话协议号应被记录");
                    assertTrue(indexOf(actual, utf8("\"protocol\":      773")) > 0,
                            "响应应携带客户端协议号，实得：" + new String(actual, StandardCharsets.UTF_8));
                    assertNull(channel.readInbound(), "状态查询不应再进入下游解码器");
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
        } finally {
            template.dispose();
        }
    }

    /** 心跳包应回出 10 字节 pong（长度前缀 + 包号 + 原样时间戳），随后关闭连接。 */
    @Test
    void fastPathEchoesPingTimestampAndCloses() {
        ResponseTemplate template = sampleTemplate();
        try {
            FastPathHostStub host = new FastPathHostStub(template, true);
            EmbeddedChannel channel = fastPathChannel(host);
            try {
                long time = 0x0102030405060708L;
                channel.writeInbound(Unpooled.wrappedBuffer(
                        concat(handshake(PROTOCOL, "h", 25565, 1),
                                frame(new byte[]{0x00}),
                                frame(pingBody(time)))));
                assertNotNull(channel.readOutbound(), "状态响应");
                ByteBuf pong = channel.readOutbound();
                assertNotNull(pong, "心跳应答");
                byte[] raw = drain(pong);
                assertArrayEquals(new byte[]{9, 0x01, 1, 2, 3, 4, 5, 6, 7, 8}, raw,
                        "pong 必须是 9 字节载荷 + 长度前缀，并原样回传时间戳");
                assertFalse(channel.isActive(), "心跳后应关闭连接（客户端走被动关闭）");
            } finally {
                channel.finishAndReleaseAll();
            }
        } finally {
            template.dispose();
        }
    }

    /** 登录意图必须原样透传给原生解码器，快速路径自移除。 */
    @Test
    void loginHandshakeIsHandedOffToNativePipeline() {
        ResponseTemplate template = sampleTemplate();
        try {
            FastPathHostStub host = new FastPathHostStub(template, true);
            Capture capture = new Capture();
            EmbeddedChannel channel = fastPathChannel(host, capture);
            try {
                channel.writeInbound(Unpooled.wrappedBuffer(
                        handshake(PROTOCOL, "mc.example.com", 25565, 2)));
                assertNotNull(capture.lastType, "登录握手应被原生解码器接受");
                assertTrue(capture.lastType.contains("Handshake"),
                        "原生解码器应产出握手包，实得：" + capture.lastType);
                assertNull(channel.pipeline().get(StatusFastPath.NAME), "快速路径应从管线移除");
                assertEquals(0, host.statusHandshakes.get(), "登录不应计为状态握手");
            } finally {
                channel.finishAndReleaseAll();
            }
        } finally {
            template.dispose();
        }
    }

    /** 状态阶段收到带载荷的请求 / 长度错误的心跳，必须断开且不写出任何响应。 */
    @Test
    void malformedStatusPacketsAreRejectedWithoutResponse() {
        ResponseTemplate template = sampleTemplate();
        try {
            // 带多余载荷的状态请求
            FastPathHostStub host = new FastPathHostStub(template, true);
            EmbeddedChannel channel = fastPathChannel(host);
            try {
                channel.writeInbound(Unpooled.wrappedBuffer(
                        concat(handshake(PROTOCOL, "h", 25565, 1),
                                frame(new byte[]{0x00, 0x00}))));
                assertNull(channel.readOutbound(), "畸形请求不应得到响应");
                assertFalse(channel.isActive(), "畸形请求应断开连接");
            } finally {
                channel.finishAndReleaseAll();
            }

            // 心跳载荷不足 8 字节
            FastPathHostStub host2 = new FastPathHostStub(template, true);
            EmbeddedChannel channel2 = fastPathChannel(host2);
            try {
                long time = 1L;
                byte[] shortPing = frame(new byte[]{0x01, 0, 0, 0, 0, 0, 0, 0});
                channel2.writeInbound(Unpooled.wrappedBuffer(
                        concat(handshake(PROTOCOL, "h", 25565, 1),
                                frame(new byte[]{0x00}), shortPing)));
                assertNotNull(channel2.readOutbound(), "状态响应");
                assertNull(channel2.readOutbound(), "长度错误的心跳不应得到 pong");
            } finally {
                channel2.finishAndReleaseAll();
            }
        } finally {
            template.dispose();
        }
    }

    /**
     * 模式开关必须按「每条连接」实时读取：关掉后新连接不得再挂快速路径。
     *
     * <p>这条约束来自真实代理的行为——{@code ServerBootstrap.childHandler} 在端口绑定
     * 时已固化，替换 holder 不会影响已监听端口，所以切换只能靠连接建立时判断。</p>
     */
    @Test
    void installSkipsFastPathWhenHostDisabled() {
        ResponseTemplate template = sampleTemplate();
        try {
            FastPathHostStub host = new FastPathHostStub(template, true);
            // 线上管线的帧解码器固定叫 frame-decoder，注入点按名字定位
            EmbeddedChannel channel = new EmbeddedChannel();
            channel.pipeline().addLast(Connections.FRAME_DECODER,
                    new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND));
            try {
                assertTrue(Injector.install(channel.pipeline(), host), "启用时应挂载");
                assertNotNull(channel.pipeline().get(StatusFastPath.NAME));

                host.enabled = false;
                EmbeddedChannel disabled = new EmbeddedChannel();
                disabled.pipeline().addLast(Connections.FRAME_DECODER,
                        new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND));
                try {
                    assertFalse(Injector.install(disabled.pipeline(), host), "关闭后不得挂载");
                    assertNull(disabled.pipeline().get(StatusFastPath.NAME));
                } finally {
                    disabled.finishAndReleaseAll();
                }
            } finally {
                channel.finishAndReleaseAll();
            }
        } finally {
            template.dispose();
        }
    }

    /** 缺少帧解码器的连接必须安全跳过（不抛异常），且不影响该连接的原生流程。 */
    @Test
    void installSkipsWhenFrameDecoderMissing() {
        ResponseTemplate template = sampleTemplate();
        try {
            FastPathHostStub host = new FastPathHostStub(template, true);
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                assertFalse(Injector.install(channel.pipeline(), host), "无帧解码器时不挂载");
                assertNull(channel.pipeline().get(StatusFastPath.NAME));
                assertTrue(channel.isActive(), "连接不应被关闭");
            } finally {
                channel.finishAndReleaseAll();
            }
        } finally {
            template.dispose();
        }
    }

    // ------------------------------------------------------------------
    // 管线与字节构造
    // ------------------------------------------------------------------

    private static ResponseTemplate sampleTemplate() {
        return ResponseTemplate.compile(
                "{\"text\":\"中文😀\"}", "MikuMOTD", null, 0, 3, 20,
                List.of(new SampleEntry(UUID.randomUUID(), "玩家甲")));
    }

    private static EmbeddedChannel frameChannel() {
        return new EmbeddedChannel(
                new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND));
    }

    /** 与线上一致的入站顺序：帧解码器 → 快速路径 → 原生包解码器 → 捕获器。 */
    private static EmbeddedChannel fastPathChannel(FastPathHost host) {
        return fastPathChannel(host, new Capture());
    }

    private static EmbeddedChannel fastPathChannel(FastPathHost host, Capture capture) {
        return new EmbeddedChannel(
                new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND),
                new StatusFastPath(host),
                new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        // 下游收到任何东西都记录下来并释放，便于断言“不应触达”
                        capture.lastType = msg.getClass().getSimpleName();
                        if (msg instanceof io.netty.util.ReferenceCounted counted) {
                            counted.release();
                        }
                    }
                });
    }

    /** 记录抵达下游（原生解码器之后）的包类型。 */
    private static final class Capture {
        private String lastType;
    }

    private static byte[] handshake(int protocol, String host, int port, int next) {
        ByteBuf payload = Unpooled.buffer();
        try {
            writeVarInt(payload, 0x00);
            writeVarInt(payload, protocol);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(payload, hostBytes.length);
            payload.writeBytes(hostBytes);
            payload.writeShort(port);
            writeVarInt(payload, next);
            return frame(drain(payload));
        } finally {
            payload.release();
        }
    }

    private static byte[] pingBody(long time) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(0x01);
            buf.writeLong(time);
            return drain(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] frame(byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        try {
            writeVarInt(buf, body.length);
            buf.writeBytes(body);
            return drain(buf);
        } finally {
            buf.release();
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static byte[] drain(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 首部 VarInt 占用的字节数。 */
    private static int prefixLength(byte[] data) {
        int i = 0;
        while (i < data.length && (data[i] & 0x80) != 0) {
            i++;
        }
        return i + 1;
    }

    /** 最小宿主实现：只回一个模板帧，其余能力按默认值。 */
    private static final class FastPathHostStub implements FastPathHost {

        private final ResponseTemplate template;
        private final boolean directWrite;
        volatile boolean enabled = true;
        final AtomicInteger statusHandshakes = new AtomicInteger();
        final AtomicInteger lastHandshakeProtocol = new AtomicInteger(-1);

        FastPathHostStub(ResponseTemplate template, boolean directWrite) {
            this.template = template;
            this.directWrite = directWrite;
        }

        @Override
        public boolean fastPathEnabled() {
            return this.enabled;
        }

        @Override
        public ByteBuf selectResponse(int protocol) {
            return this.template.acquire(protocol);
        }

        @Override
        public boolean directWrite() {
            return this.directWrite;
        }

        @Override
        public boolean allowImproperPings() {
            return false;
        }

        @Override
        public void onStatusHandshake(SocketAddress remote, int protocol) {
            this.statusHandshakes.incrementAndGet();
            this.lastHandshakeProtocol.set(protocol);
        }

        @Override
        public void logImproperPing(SocketAddress remote, String detail) {
        }

        @Override
        public void logFastPathFault(SocketAddress remote, Throwable cause) {
        }
    }
}
