package com.mikumc.motd.ping;

import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * 单条 MOTD 响应的字节级模板。
 *
 * <p>响应 JSON 在（重）加载时一次性渲染为完整的线上帧字节（含 VarInt 长度前缀），
 * 在线/最大人数与真实玩家列表内嵌帧内，人数或名单变化时整体重建（指纹相同则跳过）。
 * ping 路径上只做一次零拷贝切片（retainedDuplicate），没有任何解码、序列化与字符串操作。</p>
 *
 * <p>协议号不写进共享帧：每个协议号首次出现时由模板帧复制出一份专属帧并缓存，
 * 之后同协议 ping 依旧零拷贝命中。若改为「共享帧上原地覆写协议号」，不同位数
 * 的协议号（如 47 与 773）并发时会把字节交错成「数字中间夹空格」，产生非法 JSON。</p>
 *
 * <p>线程模型：构造与 {@link #dispose} 在配置线程；{@link #update} 在调度线程（低频）；
 * {@link #acquire} 在连接的事件循环线程（高频）。帧字节、槽位偏移与列表快照作为
 * 单个 {@link Snapshot} 一次性发布，读侧因此不会把「旧帧」与「新偏移」组合起来。</p>
 *
 * <p>快照换代（{@link #update}）会释放旧帧，因此 {@link #acquire} 采用「先抢引用」的顺序：
 * 抢占失败即说明本代快照刚被淘汰，此时重读当前快照重试。热路径保持无锁。</p>
 */
public final class ResponseTemplate {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final int COUNT_WIDTH = 8;
    private static final int PROTOCOL_WIDTH = 9;
    /**
     * 每代快照的协议号帧槽位数（必须是 2 的幂，按 {@code protocol & mask} 定位）。
     * 实际同时出现的客户端协议版本通常只有个位数，16 个槽位足够；
     * 槽位耗尽时退化为「不缓存」，每次 ping 复制一次模板帧，只影响性能不影响正确性。
     */
    private static final int PROTOCOL_SLOTS = 16;
    private static final int PROTOCOL_MASK = PROTOCOL_SLOTS - 1;
    /** 客户端悬停列表最多展示的玩家行数（与原生代理一致）。 */
    public static final int MAX_SAMPLE_ROWS = 12;

    /** 玩家列表行：玩家 UUID 与 ID 文本（来自真实在线玩家）。 */
    public record SampleEntry(UUID uuid, String name) {
    }

    /**
     * 不可变共享帧快照。帧字节、槽位偏移与列表快照必须同源发布，
     * 否则刷新期间「旧帧 + 新偏移」会让数字被写到 JSON 的任意位置。
     */
    private static final class Snapshot {

        /** 模板帧：协议槽为占位值，仅用于复制出各协议号的专属帧。 */
        final ByteBuf template;
        /** 协议槽在帧内的绝对字节偏移；人数/名单变化会改变布局，故必须随快照一起发布。 */
        final int protocolOffset;
        final int online;
        final int max;
        final List<SampleEntry> sample;
        final long fingerprint;
        /**
         * 协议号帧缓存：按 {@code protocol & PROTOCOL_MASK} 直接下标，避免热路径上的
         * {@code Integer} 装箱与哈希。槽位被别的协议号占用时按「未命中」处理，
         * 该次 ping 使用临时帧（不影响正确性）。
         */
        final AtomicReferenceArray<ProtocolSlot> frames =
                new AtomicReferenceArray<>(PROTOCOL_SLOTS);
        /** 事件模式兜底的 ServerPing，首次取用时才构建（快速路径下永不构建）。 */
        volatile ServerPing compat;

        Snapshot(ByteBuf template, int protocolOffset, int online, int max,
                 List<SampleEntry> sample, long fingerprint) {
            this.template = template;
            this.protocolOffset = protocolOffset;
            this.online = online;
            this.max = max;
            this.sample = sample;
            this.fingerprint = fingerprint;
        }
    }

    /** 一个协议号槽位：协议号与其专属帧成对发布（不可变，便于无锁读取）。 */
    private record ProtocolSlot(int protocol, ByteBuf frame) {
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
     * @param placeholderProtocol 协议槽占位值（真正的协议号在 {@link #acquire} 时按客户端填入）
     */
    public static ResponseTemplate compile(String descriptionJson, String versionName,
                                           String faviconUrl, int placeholderProtocol,
                                           int online, int max, List<SampleEntry> sample) {
        ResponseTemplate template = new ResponseTemplate(descriptionJson, versionName,
                faviconUrl, placeholderProtocol);
        template.snapshot = template.build(online, max, List.copyOf(sample));
        return template;
    }

    /** 渲染并封装出一代快照。 */
    private Snapshot build(int online, int max, List<SampleEntry> rows) {
        Rendered rendered = renderFrame(this.descriptionJson, this.versionName, this.faviconUrl,
                this.placeholderProtocol, online, max, rows);
        return new Snapshot(rendered.frame, rendered.protocolOffset, online, max, rows,
                fingerprint(online, max, rows));
    }

    /**
     * 取一条可直接写出的完整响应帧（自带 VarInt 长度前缀）。
     * 调用方写出后必须释放（引用计数已加一）。模板已销毁时返回 null。
     *
     * <p>出站管线不会经过帧编码器（处理器挂在 pipeline 前段，outbound 向 head 传播），
     * 因此无论直写还是走 writeAndFlush，都必须使用完整帧。</p>
     *
     * <p>{@link #frameFor} 在返回前已把引用加一，调用方拿到的句柄必然存活。
     * 若恰逢换代导致这一代取不到帧，则重读最新快照再取。刷新间隔最短 100ms，
     * 连续多次都恰好撞上换代的概率可忽略；这里给 4 次机会以覆盖极端并发场景。</p>
     */
    public ByteBuf acquire(int protocol) {
        for (int attempt = 0; attempt < 4; attempt++) {
            Snapshot snap = this.snapshot;
            if (snap == null) {
                // 已销毁（重载/关服）：按「未就绪」处理，由调用方关闭连接
                return null;
            }
            ByteBuf frame = this.frameFor(snap, protocol);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    /**
     * 取该协议号的帧，返回值为已 retain 的独立副本（调用方负责释放）。
     *
     * <p>未命中时新建专属帧并写入槽位，同时在锁内完成 retain 再返回：
     * 这样即使紧接着发生快照换代，本线程手上的引用也不会被回收。
     * 槽位被别的协议号占用时归还快照那一份引用后替换（槽位数远大于实际
     * 并存的客户端版本数，替换仅发生在极端混杂版本场景）。</p>
     *
     * @return null 表示该快照已被换代
     */
    private ByteBuf frameFor(Snapshot snap, int protocol) {
        int slot = protocol & PROTOCOL_MASK;
        ProtocolSlot cached = snap.frames.get(slot);
        if (cached != null && cached.protocol() == protocol) {
            try {
                return cached.frame().retainedDuplicate();
            } catch (RuntimeException e) {
                // 本代快照刚被释放：交由调用方按「未就绪」处理
                return null;
            }
        }
        synchronized (this.lifecycle) {
            if (this.snapshot != snap) {
                return null;
            }
            ProtocolSlot existing = snap.frames.get(slot);
            if (existing != null && existing.protocol() == protocol) {
                return existing.frame().retainedDuplicate();
            }
            ByteBuf fresh = snap.template.copy();
            // 协议槽为该帧私有内存：此处的写入不与任何其它 ping 共享。
            // 偏移取自本代快照——人数/名单变化会改变帧布局，绝不能沿用模板级常量。
            writePaddedInt(fresh, snap.protocolOffset, PROTOCOL_WIDTH, protocol);
            snap.frames.set(slot, new ProtocolSlot(protocol, fresh));
            if (existing != null) {
                // 只归还快照那一份引用（仍被读方持有的副本不受影响）
                existing.frame().release();
            }
            return fresh.retainedDuplicate();
        }
    }

    /** 事件模式兜底用的 ServerPing（快速路径不可用时的降级路径）。 */
    public ServerPing acquireCompat() {
        Snapshot snap = this.snapshot;
        if (snap == null) {
            return null;
        }
        ServerPing local = snap.compat;
        if (local == null) {
            local = buildCompat(snap);
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
        Rendered rendered = renderFrame(this.descriptionJson, this.versionName, this.faviconUrl,
                this.placeholderProtocol, online, max, rows);
        Snapshot fresh = new Snapshot(rendered.frame, rendered.protocolOffset, online, max, rows,
                fingerprint);
        synchronized (this.lifecycle) {
            Snapshot latest = this.snapshot;
            if (latest == null || latest.fingerprint == fingerprint) {
                // 期间已换代或已销毁：丢弃本次渲染结果，避免泄漏
                releaseSnapshot(fresh);
                return;
            }
            this.snapshot = fresh;
            releaseSnapshot(latest);
        }
    }

    private static long fingerprint(int online, int max, List<SampleEntry> sample) {
        long hash = (long) online * 1_000_000_007L + (long) max * 10_000_000_019L;
        for (SampleEntry row : sample) {
            hash = hash * 31L + row.uuid().hashCode();
            hash = hash * 31L + row.name().hashCode();
        }
        return hash;
    }

    private static Rendered renderFrame(String descriptionJson, String versionName,
                                        String faviconUrl, int placeholderProtocol,
                                        int online, int max, List<SampleEntry> sample) {
        StringBuilder sb = new StringBuilder(1024 + descriptionJson.length());
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
        sb.append(",\"name\":\"").append(escapeJson(versionName)).append("\"},\"description\":")
                .append(descriptionJson);
        if (faviconUrl != null) {
            sb.append(",\"favicon\":\"").append(faviconUrl).append('"');
        }
        sb.append('}');

        writeAsciiNumber(sb, onlineAt, COUNT_WIDTH, online);
        writeAsciiNumber(sb, maxAt, COUNT_WIDTH, max);
        writeAsciiNumber(sb, protocolAt, PROTOCOL_WIDTH, placeholderProtocol);
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

    /** 释放一整套快照帧（模板 + 各协议号副本）。 */
    private static void releaseSnapshot(Snapshot snap) {
        snap.template.release();
        for (int i = 0; i < PROTOCOL_SLOTS; i++) {
            ProtocolSlot slot = snap.frames.get(i);
            if (slot != null) {
                slot.frame().release();
                snap.frames.set(i, null);
            }
        }
    }

    /** 销毁模板：置空当前快照并释放其帧；重复调用为空操作。 */
    public void dispose() {
        Snapshot current;
        synchronized (this.lifecycle) {
            current = this.snapshot;
            this.snapshot = null;
        }
        if (current != null) {
            releaseSnapshot(current);
        }
    }

    /** 一次渲染的产物：完整帧与其内部协议槽的绝对字节偏移。 */
    private record Rendered(ByteBuf frame, int protocolOffset) {
    }
}
