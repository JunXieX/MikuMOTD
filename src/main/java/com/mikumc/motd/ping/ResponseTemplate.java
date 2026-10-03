package com.mikumc.motd.ping;

import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * 单条 MOTD 响应的字节级模板。
 *
 * <p>响应 JSON 在（重）加载时一次性渲染为完整的线上帧字节（含 VarInt 长度前缀），
 * 在线/最大人数与真实玩家列表内嵌帧内，人数或名单变化时整体重建（指纹相同则跳过）。
 * ping 路径上只做一次零拷贝切片（retainedDuplicate），没有任何解码、序列化与字符串操作。</p>
 *
 * <p>协议号槽位与人数槽位同一语义：首个客户端的协议被原位锚定进共享帧，
 * 之后同协议 ping 零拷贝命中，异协议 ping 一次 9 字节覆写（瞬时撕裂仅影响显示，可接受）。</p>
 *
 * <p>线程模型：构造与 {@link #dispose} 在配置线程；{@link #update} 在调度线程（低频）；
 * {@link #acquire} 在连接的事件循环线程（高频）。</p>
 */
public final class ResponseTemplate {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final int COUNT_WIDTH = 8;
    private static final int PROTOCOL_WIDTH = 9;
    /** 客户端悬停列表最多展示的玩家行数（与原生代理一致）。 */
    public static final int MAX_SAMPLE_ROWS = 12;

    /** 玩家列表行：玩家 UUID 与 ID 文本（来自真实在线玩家）。 */
    public record SampleEntry(UUID uuid, String name) {
    }

    private final String descriptionJson;
    private final String versionName;
    private final String faviconUrl;
    private final int placeholderProtocol;

    private int protocolOffset;
    private int onlineOffset;
    private int maxOffset;
    private int prefixLength;

    private volatile ByteBuf fullFrame;
    private volatile ServerPing compatPing;
    /** 共享帧当前锚定的协议号；-1 表示尚未锚定。 */
    private volatile int anchoredProtocol = -1;
    /** 上次重建指纹（人数 + 名单），相同则跳过重建。 */
    private volatile long lastFingerprint = Long.MIN_VALUE;

    private ResponseTemplate(String descriptionJson, String versionName,
                             String faviconUrl, int placeholderProtocol) {
        this.descriptionJson = descriptionJson;
        this.versionName = versionName;
        this.faviconUrl = faviconUrl;
        this.placeholderProtocol = placeholderProtocol;
    }

    /**
     * @param descriptionJson     MiniMessage 反序列化后经 Gson 序列化的描述 JSON 子树
     * @param faviconUrl          图标 data URL，无图标为 null
     * @param placeholderProtocol 协议槽初始值（首个客户端 ping 后被真实协议锚定）
     */
    public static ResponseTemplate compile(String descriptionJson, String versionName,
                                           String faviconUrl, int placeholderProtocol,
                                           int online, int max, List<SampleEntry> sample) {
        ResponseTemplate template = new ResponseTemplate(descriptionJson, versionName,
                faviconUrl, placeholderProtocol);
        ResponseTemplate.Offsets offsets = new ResponseTemplate.Offsets();
        template.fullFrame = template.renderFrame(online, max, sample, offsets);
        template.compatPing = template.buildCompat(online, max, sample);
        template.applyOffsets(offsets);
        template.lastFingerprint = template.fingerprint(online, max, sample);
        return template;
    }

    private void applyOffsets(Offsets offsets) {
        this.prefixLength = offsets.prefixLength;
        this.protocolOffset = offsets.protocol + offsets.jsonOffset;
        this.onlineOffset = offsets.online + offsets.jsonOffset;
        this.maxOffset = offsets.max + offsets.jsonOffset;
    }

    /**
     * 取一条可直接写出的完整响应帧（自带 VarInt 长度前缀）。
     * 调用方写出后必须释放（引用计数已加一）。模板已销毁时返回 null。
     *
     * <p>出站管线不会经过帧编码器（处理器挂在 pipeline 前段，outbound 向 head 传播），
     * 因此无论直写还是走 writeAndFlush，都必须使用完整帧。</p>
     */
    public ByteBuf acquire(int protocol) {
        ByteBuf frame = this.fullFrame;
        if (frame == null) {
            return null;
        }
        if (protocol != this.anchoredProtocol) {
            // 协议槽懒锚定：与人数槽同一覆写语义，主流协议仅在首个 ping 写一次
            writePaddedInt(frame, this.protocolOffset, PROTOCOL_WIDTH, protocol);
            this.anchoredProtocol = protocol;
        }
        return frame.retainedDuplicate();
    }

    /** 事件模式兜底用的 ServerPing（快速路径不可用时的降级路径）。 */
    public ServerPing acquireCompat() {
        return this.compatPing;
    }

    /**
     * 人数/名单刷新（调度线程）：指纹（人数 + 名单）未变时跳过，否则整体重建。
     */
    public void update(int online, int max, List<SampleEntry> sample) {
        long fingerprint = fingerprint(online, max, sample);
        if (fingerprint == this.lastFingerprint) {
            return;
        }
        ByteBuf old = this.fullFrame;
        if (old == null) {
            return;
        }
        this.lastFingerprint = fingerprint;
        ResponseTemplate.Offsets offsets = new ResponseTemplate.Offsets();
        ByteBuf fresh = this.renderFrame(online, max, sample, offsets);
        this.fullFrame = fresh;
        this.compatPing = this.buildCompat(online, max, sample);
        this.applyOffsets(offsets);
        this.anchoredProtocol = -1;
        old.release();
    }

    private static long fingerprint(int online, int max, List<SampleEntry> sample) {
        long hash = (long) online * 1_000_000_007L + (long) max * 10_000_000_019L;
        for (SampleEntry row : sample) {
            hash = hash * 31L + row.uuid().hashCode();
            hash = hash * 31L + row.name().hashCode();
        }
        return hash;
    }

    private ByteBuf renderFrame(int online, int max, List<SampleEntry> sample, Offsets offsets) {
        StringBuilder sb = new StringBuilder(1024 + this.descriptionJson.length());
        sb.append("{\"players\":{\"online\":");
        offsets.online = sb.length();
        sb.append("        ");
        sb.append(",\"max\":");
        offsets.max = sb.length();
        sb.append("        ");
        sb.append(",\"sample\":[");
        for (int i = 0; i < sample.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SampleEntry row = sample.get(i);
            sb.append("{\"id\":\"").append(row.uuid()).append("\",\"name\":\"")
                    .append(escapeJson(row.name())).append("\"}");
        }
        sb.append("]},\"version\":{\"protocol\":");
        offsets.protocol = sb.length();
        sb.append("         ");
        sb.append(",\"name\":\"").append(escapeJson(this.versionName)).append("\"},\"description\":")
                .append(this.descriptionJson);
        if (this.faviconUrl != null) {
            sb.append(",\"favicon\":\"").append(this.faviconUrl).append('"');
        }
        sb.append('}');

        writeAsciiNumber(sb, offsets.online, COUNT_WIDTH, online);
        writeAsciiNumber(sb, offsets.max, COUNT_WIDTH, max);
        writeAsciiNumber(sb, offsets.protocol, PROTOCOL_WIDTH, this.placeholderProtocol);
        return toFrame(sb.toString(), offsets);
    }

    private ServerPing buildCompat(int online, int max, List<SampleEntry> sample) {
        ServerPing.Builder builder = ServerPing.builder()
                .version(new ServerPing.Version(this.placeholderProtocol, this.versionName))
                .description(GSON.deserialize(this.descriptionJson))
                .onlinePlayers(Math.max(0, online))
                .maximumPlayers(Math.max(0, max));
        if (this.faviconUrl != null) {
            builder.favicon(new Favicon(this.faviconUrl));
        }
        if (!sample.isEmpty()) {
            builder.samplePlayers(sample.stream()
                    .map(row -> new ServerPing.SamplePlayer(row.name(), row.uuid()))
                    .toArray(ServerPing.SamplePlayer[]::new));
        }
        return builder.build();
    }

    private static ByteBuf toFrame(String json, Offsets offsets) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int bodyLength = 1 + varIntBytes(bytes.length) + bytes.length;
        int frameLength = varIntBytes(bodyLength) + bodyLength;
        // 帧布局：[varint bodyLen][packet id][varint jsonLen][json]
        offsets.prefixLength = frameLength - bodyLength;
        offsets.jsonOffset = offsets.prefixLength + 1 + varIntBytes(bytes.length);
        // 渲染期记录的 char 偏移统一换算为帧内绝对字节偏移（JSON 可含多字节内容）
        offsets.online = byteOffsetOf(json, offsets.online);
        offsets.max = byteOffsetOf(json, offsets.max);
        offsets.protocol = byteOffsetOf(json, offsets.protocol);

        ByteBuf frame = Unpooled.directBuffer(frameLength);
        writeVarInt(frame, bodyLength);
        frame.writeByte(0x00);
        writeVarInt(frame, bytes.length);
        frame.writeBytes(bytes);
        return frame;
    }

    /** JSON 内 char 偏移 → UTF-8 字节偏移（仅构造期调用）。 */
    static int byteOffsetOf(String json, int charOffset) {
        int bytes = 0;
        for (int i = 0; i < charOffset; i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < charOffset
                    && Character.isLowSurrogate(json.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** 定宽右对齐整数的原位字节覆写（最低位恒为数字，其余补空格）。 */
    static void writePaddedInt(ByteBuf buf, int offset, int width, int value) {
        value = Math.max(0, Math.min(value, width == COUNT_WIDTH ? 99_999_999 : 999_999_999));
        for (int i = width - 1; i >= 0; i--) {
            if (value > 0 || i == width - 1) {
                buf.setByte(offset + i, '0' + value % 10);
                value /= 10;
            } else {
                buf.setByte(offset + i, ' ');
            }
        }
    }

    /** 渲染期版本：往 StringBuilder 的定宽槽位里写右对齐数字（与 writePaddedInt 同一视觉格式）。 */
    static void writeAsciiNumber(StringBuilder sb, int at, int width, int value) {
        value = Math.max(0, Math.min(value, width == COUNT_WIDTH ? 99_999_999 : 999_999_999));
        int end = at + width;
        for (int i = end - 1; i >= at; i--) {
            if (value > 0 || i == end - 1) {
                sb.setCharAt(i, (char) ('0' + value % 10));
                value /= 10;
            } else {
                sb.setCharAt(i, ' ');
            }
        }
    }

    static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    static String escapeJson(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public void dispose() {
        ByteBuf frame = this.fullFrame;
        if (frame != null && frame.refCnt() != 0) {
            frame.release();
        }
        this.fullFrame = null;
        this.compatPing = null;
    }

    /** 构造期 JSON 偏移记录。 */
    static final class Offsets {
        int protocol = -1;
        int online = -1;
        int max = -1;
        int jsonOffset;
        int prefixLength;
    }
}
