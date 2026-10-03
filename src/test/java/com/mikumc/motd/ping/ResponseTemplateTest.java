package com.mikumc.motd.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mikumc.motd.ping.ResponseTemplate.SampleEntry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
            assertTrue(next.contains("\"protocol\":      758"), "异协议 ping 应重锚协议号：" + next);
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
