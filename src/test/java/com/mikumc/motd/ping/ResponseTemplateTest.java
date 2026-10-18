package com.mikumc.motd.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mikumc.motd.ping.ResponseTemplate.SampleEntry;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 字节模板的纯函数与偏移正确性测试：曾因 char/byte 偏移、
 * 帧前缀漏算与多字节内容引入过回归，此处作为防回归基线。
 */
class ResponseTemplateTest {

    @Test
    void byteOffsetCountsAsciiAsOne() {
        assertEquals(5, ResponseTemplate.byteOffsetOf("abcde", 5));
    }

    @Test
    void byteOffsetCountsLatinSupplementAsTwo() {
        assertEquals(2, ResponseTemplate.byteOffsetOf("»", 1));
    }

    @Test
    void byteOffsetCountsCjkAsThree() {
        assertEquals(6, ResponseTemplate.byteOffsetOf("测试", 2));
    }

    @Test
    void byteOffsetCountsSurrogatePairAsFour() {
        assertEquals(5, ResponseTemplate.byteOffsetOf("a😀b", 3));
    }

    /**
     * 孤立代理项在 UTF-8 编码时被替换为 '?'（单字节），若按 3 字节累计会让
     * 其后所有数字槽整体漂移，进而把人数/协议写到错误的 JSON 位置。
     */
    @Test
    void byteOffsetTreatsLoneSurrogateAsSingleByte() {
        String loneHigh = "a\uD83Db";   // 不成对的高代理项
        String loneLow = "a\uDE00b";    // 不成对的低代理项
        for (String sample : List.of(loneHigh, loneLow)) {
            // 累计值必须与真实编码字节数一致（这正是偏移不漂移的前提）
            assertEquals(sample.getBytes(StandardCharsets.UTF_8).length,
                    ResponseTemplate.byteOffsetOf(sample, sample.length()),
                    "孤立代理项应计为 1 字节：" + sample);
        }
        assertEquals(3, ResponseTemplate.byteOffsetOf(loneHigh, 3));
        assertEquals(3, ResponseTemplate.byteOffsetOf(loneLow, 3));
        // 紧邻的成对代理项仍按 4 字节计
        assertEquals(5, ResponseTemplate.byteOffsetOf("a😀b", 3));
    }

    @Test
    void varIntBytesMatchesProtocolWidths() {
        assertEquals(1, ResponseTemplate.varIntBytes(0));
        assertEquals(1, ResponseTemplate.varIntBytes(127));
        assertEquals(2, ResponseTemplate.varIntBytes(128));
        assertEquals(3, ResponseTemplate.varIntBytes(65_536));
    }

    @Test
    void writePaddedIntRightAlignsWithSpaceFill() {
        ByteBuf buf = Unpooled.directBuffer(8);
        for (int i = 0; i < 8; i++) {
            buf.writeByte(' ');
        }
        ResponseTemplate.writePaddedInt(buf, 0, 8, 1000);
        byte[] out = new byte[8];
        buf.readBytes(out);
        assertEquals("    1000", new String(out, StandardCharsets.US_ASCII));
    }

    @Test
    void writePaddedIntClampsToWidth() {
        ByteBuf buf = Unpooled.directBuffer(8);
        for (int i = 0; i < 8; i++) {
            buf.writeByte(' ');
        }
        ResponseTemplate.writePaddedInt(buf, 0, 8, Integer.MAX_VALUE);
        byte[] out = new byte[8];
        buf.readBytes(out);
        assertEquals("99999999", new String(out, StandardCharsets.US_ASCII));
    }

    @Test
    void escapeJsonHandlesQuotesControlCharsAndUtf8Passthrough() {
        assertEquals("\\\"", ResponseTemplate.escapeJson("\""));
        assertEquals("\\\\", ResponseTemplate.escapeJson("\\"));
        assertEquals("\\b", ResponseTemplate.escapeJson("\b"));
        assertEquals("\\n", ResponseTemplate.escapeJson("\n"));
        assertEquals("\\t", ResponseTemplate.escapeJson("\t"));
        assertEquals("\\u0001", ResponseTemplate.escapeJson("\u0001"));
        assertEquals("测", ResponseTemplate.escapeJson("测"));
    }

    @Test
    void compiledTemplateKeepsOffsetsAcrossNonAsciiContent() {
        // 描述与玩家名含 CJK 与表情符号：数字槽偏移必须落到正确字节位置
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"color\":\"green\",\"text\":\"中文描述😀\"}",
                "版本名😀",
                null,
                0,
                0, 1,
                List.of(new SampleEntry(UUID.randomUUID(), "玩家甲")));
        try {
            String json = readFrameJson(template.acquire(773));
            assertTrue(json.contains("\"online\":       0,"), "初始在线人数：" + json);
            assertTrue(json.contains("\"max\":       1,"), "初始最大人数：" + json);
            assertTrue(json.contains("\"protocol\":      773"), "协议号锚定：" + json);
            assertTrue(json.contains("中文描述"), "描述应完整保留：" + json);
            assertTrue(json.contains("玩家甲"), "玩家列表应完整保留：" + json);
        } finally {
            template.dispose();
        }
    }

    @Test
    void updateRewritesCountsAndSampleAtCorrectOffsets() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"描述\"}", "MikuMOTD", null, 0, 0, 1, List.of());
        try {
            template.update(12345, 6789, List.of(
                    new SampleEntry(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch")));
            String json = readFrameJson(template.acquire(773));
            assertTrue(json.contains("\"online\":   12345,"), "覆写后的在线人数：" + json);
            assertTrue(json.contains("\"max\":    6789,"), "覆写后的最大人数：" + json);
            assertTrue(json.contains("Notch"), "覆写后的玩家列表：" + json);
            assertTrue(json.contains("\"protocol\":      773"), "协议号锚定：" + json);
        } finally {
            template.dispose();
        }
    }

    @Test
    void protocolAnchorRewritesOnProtocolChange() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"d\"}", "v", null, 0, 0, 1, List.of());
        try {
            readFrameJson(template.acquire(773));
            String next = readFrameJson(template.acquire(758));
            assertTrue(next.contains("\"protocol\":      758"), "异协议 ping 应使用自己的协议号：" + next);
        } finally {
            template.dispose();
        }
    }

    /**
     * 不同位数的协议号必须互不干扰：共享帧上原地覆写会交错出「数字中间夹空格」的字节，
     * 那是非法 JSON；每协议号独立帧则不会。
     */
    @Test
    void differingWidthProtocolsDoNotCorruptEachOther() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"d\"}", "v", null, 0, 0, 1, List.of());
        try {
            String modern = readFrameJson(template.acquire(773));
            String legacy = readFrameJson(template.acquire(47));
            String modernAgain = readFrameJson(template.acquire(773));
            assertTrue(modern.contains("\"protocol\":      773"), "现代协议：" + modern);
            assertTrue(legacy.contains("\"protocol\":       47"), "旧协议：" + legacy);
            assertTrue(modernAgain.contains("\"protocol\":      773"), "现代协议再次命中：" + modernAgain);
            // 数字槽内不允许出现空格（除前导补位）
            for (String json : List.of(modern, legacy, modernAgain)) {
                int at = json.indexOf("\"protocol\":") + "\"protocol\":".length();
                String slot = json.substring(at, at + 9);
                assertTrue(slot.trim().matches("\\d+"), "协议槽必须是纯数字：" + slot);
            }
        } finally {
            template.dispose();
        }
    }

    /** 刷新会用人数位数更多的新帧替换旧帧：偶数位宽变化也不得让协议槽错位。 */
    @Test
    void updateKeepsProtocolSlotAlignedAfterWidthChange() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"x\"}", "v", null, 0, 0, 1, List.of());
        try {
            template.update(9_999_999, 9_999_999, List.of());
            String json = readFrameJson(template.acquire(770));
            assertTrue(json.contains("\"online\": 9999999,"), "在线人数：" + json);
            assertTrue(json.contains("\"max\": 9999999,"), "最大人数：" + json);
            assertTrue(json.contains("\"protocol\":      770"), "协议号：" + json);
        } finally {
            template.dispose();
        }
    }

    /** 销毁后必须彻底失效：不再返回帧，也不允许刷新把已释放的帧「复活」。 */
    @Test
    void disposeInvalidatesTemplateAndBlocksResurrection() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"x\"}", "v", null, 0, 0, 1, List.of());
        template.dispose();
        assertNull(template.acquire(773), "销毁后不应再返回帧");
        assertNull(template.acquireCompat(), "销毁后不应再返回兜底 ping");

        // 重载/关服竞态：destroy 之后到达的刷新不得重建帧（否则永久泄漏）
        template.update(5, 10, List.of(new SampleEntry(UUID.randomUUID(), "late")));
        assertNull(template.acquire(773), "销毁后的刷新不得复活帧");
        // 幂等：重复 dispose 不应异常
        template.dispose();
    }

    /** 帧被外部持有（引用计数 > 0）时销毁不得释放底层内存，否则读方会用到已释放缓冲区。 */
    @Test
    void disposeDoesNotFreeFrameWhileDuplicateOutstanding() {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"x\"}", "v", null, 0, 0, 1, List.of());
        ByteBuf held = template.acquire(773);
        assertNotNull(held);
        template.dispose();
        try {
            assertEquals(1, held.refCnt(), "持有中的切片必须仍然有效");
            assertTrue(readFrameJson(held).contains("\"protocol\":      773"), "已取出的帧内容仍可读");
        } finally {
            held.release();
        }
        assertEquals(0, held.refCnt(), "释放后切片引用计数归零");
    }

    /** 并发「刷新 + 多协议号高频取帧」不得抛异常，每帧都必须是完整合法的 JSON。 */
    @Test
    void concurrentUpdateAndAcquireStaysConsistent() throws Exception {
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"并发😀\"}", "MikuMOTD", null, 0, 0, 1, List.of());
        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<String> firstFailure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(3);
        // 两个读线程分别用不同位数的协议号，正是共享槽位会撕裂的组合
        Runnable reader = protocolReader(template, 773, failed, firstFailure, done);
        Runnable reader2 = protocolReader(template, 47, failed, firstFailure, done);
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 2_000; i++) {
                    template.update(i, 1_000 + i, List.of(
                            new SampleEntry(UUID.randomUUID(), "玩家" + i)));
                }
            } catch (Throwable t) {
                failed.set(true);
            } finally {
                done.countDown();
            }
        });
        new Thread(reader).start();
        new Thread(reader2).start();
        writer.start();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试未在限时内完成");
        template.dispose();
        assertTrue(!failed.get(), "并发读写不应产生异常或错位：" + firstFailure.get());
    }

    private static Runnable protocolReader(ResponseTemplate template, int protocol,
                                           AtomicBoolean failed, AtomicReference<String> firstFailure,
                                           CountDownLatch done) {
        return () -> {
            String expected = "\"protocol\":" + " ".repeat(9 - Integer.toString(protocol).length())
                    + protocol;
            try {
                for (int i = 0; i < 20_000; i++) {
                    ByteBuf frame = template.acquire(protocol);
                    if (frame == null) {
                        continue;
                    }
                    try {
                        String json = readFrameJson(frame);
                        if (!json.contains(expected)) {
                            failed.set(true);
                            firstFailure.compareAndSet(null, "期望 " + expected + " 实得 " + json);
                            return;
                        }
                    } finally {
                        frame.release();
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
                firstFailure.compareAndSet(null, t.toString());
            } finally {
                done.countDown();
            }
        };
    }

    @Test
    void compatPingCarriesCountsSampleAndVersion() {
        UUID id = UUID.randomUUID();
        ResponseTemplate template = ResponseTemplate.compile(
                "{\"text\":\"hello\"}", "MikuMOTD", null, 0, 3, 20,
                List.of(new SampleEntry(id, "玩家甲")));
        try {
            ServerPing ping = template.acquireCompat();
            assertNotNull(ping);
            assertEquals(3, ping.getPlayers().orElseThrow().getOnline());
            assertEquals(20, ping.getPlayers().orElseThrow().getMax());
            assertEquals("玩家甲", ping.getPlayers().orElseThrow().getSample().get(0).getName());
            assertEquals(id, ping.getPlayers().orElseThrow().getSample().get(0).getId());
            assertEquals("MikuMOTD", ping.getVersion().getName());
        } finally {
            template.dispose();
        }
    }

    /** 读出一个完整响应帧内的 JSON 文本（帧自带长度前缀）。 */
    private static String readFrameJson(ByteBuf frame) {
        int bodyLength = readVarInt(frame);
        int packetId = readVarInt(frame);
        assertEquals(0x00, packetId, "应为状态响应包");
        int jsonLength = readVarInt(frame);
        byte[] json = new byte[jsonLength];
        frame.readBytes(json);
        assertEquals(bodyLength, 1 + varIntWidth(jsonLength) + jsonLength, "帧长度自洽");
        return new String(json, StandardCharsets.UTF_8);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int bits = 0;
        while (true) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << bits;
            if ((b & 0x80) == 0) {
                return value;
            }
            bits += 7;
        }
    }

    private static int varIntWidth(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }
}
