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
 * {@link #acquire} 在连接的事件循环线程（高频）。帧字节、协议槽偏移与列表快照作为
 * 单个 {@link Snapshot} 一次性发布，读侧因此永远不会把「旧帧」与「新偏移」组合起来，
 * 刷新期间的错位写入（会写坏 JSON 甚至越界）在结构上不可能发生。</p>
 *
 * <p>快照换代（{@link #update}）会释放旧帧，因此 {@link #acquire} 采用「先抢引用、再写协议槽」
 * 的顺序：抢占失败即说明本代快照刚被淘汰，按「未就绪」返回；抢占成功后帧已被本线程持有，
 * 写协议槽绝不可能落到已回收的内存上。热路径因此保持无锁。</p>
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

    /**
     * 不可变共享帧快照。帧字节与其内部槽位偏移必须同源发布，
     * 否则刷新期间「旧帧 + 新偏移」会让协议号被写到 JSON 的任意位置。
     */
    private static final class Snapshot {

        final ByteBuf frame;
        final int protocolOffset;
        final int online;
        final int max;
        final List<SampleEntry> sample;
        final long fingerprint;
        /** 帧内协议槽当前锚定值；-1 表示尚未锚定。 */
        volatile int anchoredProtocol = -1;
        /** 事件模式兜底的 ServerPing，首次取用时才构建（快速路径下永不构建）。 */
        volatile ServerPing compat;

        Snapshot(ByteBuf frame, int protocolOffset, int online, int max,
                 List<SampleEntry> sample, long fingerprint) {
            this.frame = frame;
            this.protocolOffset = protocolOffset;
            this.online = online;
            this.max = max;
            this.sample = sample;
            this.fingerprint = fingerprint;
        }
    }

    private final String descriptionJson;
    private final String versionName;
    private final String faviconUrl;
    private final int placeholderProtocol;

    /** 生命周期锁：{@link #update} 与 {@link #dispose} 互斥，杜绝竞态下的重复释放与帧复活。 */
    private final Object lifecycle = new Object();

    private volatile Snapshot snapshot;

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
        template.snapshot = template.build(online, max, List.copyOf(sample));
        return template;
    }

    /**
     * 取一条可直接写出的完整响应帧（自带 VarInt 长度前缀）。
     * 调用方写出后必须释放（引用计数已加一）。模板已销毁时返回 null。
     *
     * <p>出站管线不会经过帧编码器（处理器挂在 pipeline 前段，outbound 向 head 传播），
     * 因此无论直写还是走 writeAndFlush，都必须使用完整帧。</p>
     *
     * <p>顺序至关重要：先 {@code retainedDuplicate()} 抢占引用，再写协议槽。
     * 若先写后抢引用，刷新换代就可能在我们写入的瞬间释放该帧，造成向已回收内存写入。
     * 引用计数的自增是 CAS 语义：帧一旦被换代释放（计数归零）就必定抢引用失败，
     * 此时立刻重读当前快照重试。抢占成功后帧由本线程持有，写协议槽绝不可能越界。</p>
     *
     * <p>热路径无锁：常态下只有一次 volatile 读 + 一次引用计数自增。
     * 仅当恰逢刷新换代（最快 100ms 一次）才多走一次重试，因此 ping 不会因此被丢弃。</p>
     */
    public ByteBuf acquire(int protocol) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Snapshot snap = this.snapshot;
            if (snap == null) {
                // 已销毁（重载/关服）：按「未就绪」处理，由调用方关闭连接
                return null;
            }
            ByteBuf frame;
            try {
                frame = snap.frame.retainedDuplicate();
            } catch (RuntimeException e) {
                // 本代快照刚被换代释放：重读当前快照（换代最快 100ms 一次，两次连续失败不可能发生）
                continue;
            }
            if (protocol != snap.anchoredProtocol) {
                writePaddedInt(frame, snap.protocolOffset, PROTOCOL_WIDTH, protocol);
                snap.anchoredProtocol = protocol;
            }
            return frame;
        }
        return null;
    }

    /** 事件模式兜底用的 ServerPing（快速路径不可用时的降级路径）。 */
    public ServerPing acquireCompat() {
        Snapshot snap = this.snapshot;
        if (snap == null) {
            return null;
        }
        ServerPing local = snap.compat;
        if (local == null) {
            local = this.buildCompat(snap);
            snap.compat = local;
        }
        return local;
    }

    /**
     * 人数/名单刷新（调度线程）：指纹（人数 + 名单）未变时跳过，否则整体重建。
     *
     * <p>渲染（相对昂贵）在锁外完成，锁内只做换代与旧帧释放，因此不会阻塞事件循环的取帧。</p>
     */
    public void update(int online, int max, List<SampleEntry> sample) {
        List<SampleEntry> rows = List.copyOf(sample);
        long fingerprint = fingerprint(online, max, rows);
        Snapshot current = this.snapshot;
        if (current == null || fingerprint == current.fingerprint) {
            // 指纹未变，或已销毁（重载/关服竞态）：无需重建
            return;
        }
        Snapshot fresh = this.build(online, max, rows);
        synchronized (this.lifecycle) {
            Snapshot latest = this.snapshot;
            if (latest == null || latest.fingerprint == fingerprint) {
                // 期间已换代或已销毁：丢弃本次渲染结果，避免泄漏
                releaseFrame(fresh.frame);
                return;
            }
            this.snapshot = fresh;
            releaseFrame(latest.frame);
        }
    }

    /** 构造一条快照；rows 必须是不可变列表。 */
    private Snapshot build(int online, int max, List<SampleEntry> rows) {
        Rendered rendered = this.renderFrame(online, max, rows);
        return new Snapshot(rendered.frame, rendered.protocolOffset, online, max, rows,
                fingerprint(online, max, rows));
    }

    private static long fingerprint(int online, int max, List<SampleEntry> sample) {
        long hash = (long) online * 1_000_000_007L + (long) max * 10_000_000_019L;
        for (SampleEntry row : sample) {
            hash = hash * 31L + row.uuid().hashCode();
            hash = hash * 31L + row.name().hashCode();
        }
        return hash;
    }

    private Rendered renderFrame(int online, int max, List<SampleEntry> sample) {
        StringBuilder sb = new StringBuilder(1024 + this.descriptionJson.length());
        sb.append("{\"players\":{\"online\":");
        int onlineAt = sb.length();
        sb.append("        ");
        sb.append(",\"max\":");
        int maxAt = sb.length();
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
        int protocolAt = sb.length();
        sb.append("         ");
        sb.append(",\"name\":\"").append(escapeJson(this.versionName)).append("\"},\"description\":")
                .append(this.descriptionJson);
        if (this.faviconUrl != null) {
            sb.append(",\"favicon\":\"").append(this.faviconUrl).append('"');
        }
        sb.append('}');

        writeAsciiNumber(sb, onlineAt, COUNT_WIDTH, online);
        writeAsciiNumber(sb, maxAt, COUNT_WIDTH, max);
        writeAsciiNumber(sb, protocolAt, PROTOCOL_WIDTH, this.placeholderProtocol);
        return toFrame(sb.toString(), protocolAt);
    }

    private ServerPing buildCompat(Snapshot snap) {
        ServerPing.Builder builder = ServerPing.builder()
                .version(new ServerPing.Version(this.placeholderProtocol, this.versionName))
                .description(GSON.deserialize(this.descriptionJson))
                .onlinePlayers(Math.max(0, snap.online))
                .maximumPlayers(Math.max(0, snap.max));
        if (this.faviconUrl != null) {
            builder.favicon(new Favicon(this.faviconUrl));
        }
        if (!snap.sample.isEmpty()) {
            builder.samplePlayers(snap.sample.stream()
                    .map(row -> new ServerPing.SamplePlayer(row.name(), row.uuid()))
                    .toArray(ServerPing.SamplePlayer[]::new));
        }
        return builder.build();
    }

    private static Rendered toFrame(String json, int protocolCharOffset) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int bodyLength = 1 + varIntBytes(bytes.length) + bytes.length;
        int frameLength = varIntBytes(bodyLength) + bodyLength;
        // 帧布局：[varint bodyLen][packet id][varint jsonLen][json]
        int prefixLength = frameLength - bodyLength;
        int jsonOffset = prefixLength + 1 + varIntBytes(bytes.length);
        // 渲染期记录的 char 偏移换算为帧内绝对字节偏移（JSON 可含多字节内容）
        int protocolOffset = jsonOffset + byteOffsetOf(json, protocolCharOffset);

        ByteBuf frame = Unpooled.directBuffer(frameLength);
        writeVarInt(frame, bodyLength);
        frame.writeByte(0x00);
        writeVarInt(frame, bytes.length);
        frame.writeBytes(bytes);
        return new Rendered(frame, protocolOffset);
    }

    /**
     * JSON 内 char 偏移 → UTF-8 字节偏移（仅构造期调用）。
     *
     * <p>孤立代理项（未成对的 high/low surrogate）按 UTF-8 编码器的替换语义只占 1 字节，
     * 不能按 3 字节计，否则后续所有槽位偏移都会漂移。</p>
     */
    static int byteOffsetOf(String json, int charOffset) {
        int bytes = 0;
        for (int i = 0; i < charOffset; i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < charOffset && Character.isLowSurrogate(json.charAt(i + 1))) {
                    bytes += 4;
                    i++;
                } else {
                    // 孤立高代理项：UTF-8 编码为替换字符 '?'，占 1 字节
                    bytes += 1;
                }
            } else if (Character.isLowSurrogate(c)) {
                // 孤立低代理项：同样被替换为 '?'，占 1 字节
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** 定宽右对齐整数的原位字节覆写（最低位恒为数字，其余补空格）。 */
    static void writePaddedInt(ByteBuf buf, int offset, int width, int value) {
        value = clampToWidth(width, value);
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
        value = clampToWidth(width, value);
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

    /** 定宽槽位可表示的最大值（超出则截断为该宽度全 9）。 */
    private static int clampToWidth(int width, int value) {
        int max = width == COUNT_WIDTH ? 99_999_999 : 999_999_999;
        return Math.max(0, Math.min(value, max));
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
                        appendUnicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 控制字符的 Unicode 转义（手工写十六进制，避免 String.format 的格式化器分配）。 */
    private static void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            sb.append(Character.forDigit((c >> shift) & 0xF, 16));
        }
    }

    private static void releaseFrame(ByteBuf frame) {
        frame.release();
    }

    /** 销毁模板：置空当前快照并释放其帧；重复调用为空操作。 */
    public void dispose() {
        Snapshot current;
        synchronized (this.lifecycle) {
            current = this.snapshot;
            this.snapshot = null;
        }
        if (current != null) {
            releaseFrame(current.frame);
        }
    }

    /** 一次渲染的产物：完整帧与其内部协议槽的绝对字节偏移。 */
    private record Rendered(ByteBuf frame, int protocolOffset) {
    }
}
