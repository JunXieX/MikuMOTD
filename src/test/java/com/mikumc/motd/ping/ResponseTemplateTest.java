package com.mikumc.motd.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        // » 为 U+00BB，UTF-8 两字节
        assertEquals(2, ResponseTemplate.byteOffsetOf("»", 1));
    }

    @Test
    void byteOffsetCountsCjkAsThree() {
        assertEquals(6, ResponseTemplate.byteOffsetOf("测试", 2));
    }

    @Test
    void byteOffsetCountsSurrogatePairAsFour() {
        String text = "a😀b";
        // a(1) + 代理对(4)
        assertEquals(5, ResponseTemplate.byteOffsetOf(text, 3));
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
        assertEquals("\\\"a\\\\b\\n\\t\\u0001测", ResponseTemplate.escapeJson("\"a\\b\n\t\u0001测"));
    }

    @Test
    void compiledTemplateKeepsOffsetsAcrossNonAsciiContent() throws Exception {
        // 描述含 CJK 与表情符号、玩家列表含 CJK：数字槽偏移必须落到正确字节位置
        com.mikumc.motd.config.TextFormat format = com.mikumc.motd.config.TextFormat.LEGACY_AMPERSAND;
        TemplateFactory factory = new TemplateFactory(
                java.nio.file.Path.of("."), format, -1.0D,
                org.slf4j.LoggerFactory.getLogger("test"), null);
        com.mikumc.motd.config.ProfileData profile = new com.mikumc.motd.config.ProfileData(
                "版本名😀", List.of("&a中文描述😀"), List.of(), List.of("&b玩家甲"), false);

        ResponseTemplate template = factory.compile(profile, false)[0];

        ByteBuf frame = template.acquire(773);
        assertNotNull(frame);
        try {
            String json = readFrameJson(frame);
            assertTrue(json.contains("\"online\":       0,"), "初始在线人数应在正确偏移：" + json);
            assertTrue(json.contains("\"max\":       1,"), "初始最大人数应在正确偏移：" + json);
            assertTrue(json.contains("\"protocol\":      773"), "协议号应在正确偏移：" + json);
            assertTrue(json.contains("中文描述"), "描述应完整保留：" + json);
        } finally {
            frame.release();
        }

        // 人数变化后再次获取：原位覆写必须同样命中正确偏移（含多字节内容场景）
        template.update(12345, 6789);
        ByteBuf updated = template.acquire(773);
        try {
            String json = readFrameJson(updated);
            assertTrue(json.contains("\"online\":   12345,"), "覆写后的在线人数：" + json);
            assertTrue(json.contains("\"max\":    6789,"), "覆写后的最大人数：" + json);
            assertTrue(json.contains("\"protocol\":      773"), "覆写后协议号不变：" + json);
        } finally {
            updated.release();
        }
        template.dispose();
    }

    @Test
    void fixedProtocolTemplateIgnoresClientProtocol() throws Exception {
        TemplateFactory factory = new TemplateFactory(
                java.nio.file.Path.of("."), com.mikumc.motd.config.TextFormat.LEGACY_AMPERSAND, -1.0D,
                org.slf4j.LoggerFactory.getLogger("test"), null);
        com.mikumc.motd.config.ProfileData profile = new com.mikumc.motd.config.ProfileData(
                "维护中", List.of("&c维护"), List.of(), List.of(), false);

        ResponseTemplate template = factory.compile(profile, true)[0];
        ByteBuf frame = template.acquire(758);
        try {
            String json = readFrameJson(frame);
            assertTrue(json.contains("\"protocol\":        1"), "固定协议模板应恒为 1：" + json);
        } finally {
            frame.release();
        }
        assertEquals(1, template.acquireCompat(758).getVersion().getProtocol());
        template.dispose();
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
