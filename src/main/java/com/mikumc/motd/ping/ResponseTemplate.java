package com.mikumc.motd.ping;

import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单条 MOTD 响应的字节级模板。
 *
 * <p>响应 JSON 在（重）加载时一次性渲染为完整的线上帧字节（含 VarInt 长度前缀），
 * 在线/最大人数与协议号字段用定宽填充占位，人数变化时直接在原位覆写字节；
 * 不同客户端协议号则按需生成独立的协议视图（每种协议至多复制一次）。
 * ping 路径上只做一次零拷贝切片（duplicate/slice），没有任何解码、序列化与字符串操作。</p>
 *
 * <p>JSON 的字段偏移在渲染期按 char 记录、组帧时统一换算为 UTF-8 字节偏移，
 * 因此数字槽位可以位于任何多字节内容之后。</p>
 *
 * <p>线程模型：构造与 {@link #dispose} 在配置线程；{@link #update} 在调度线程（低频）；
 * {@link #acquire} 与 {@link #acquireCompat} 在连接的事件循环线程（高频）。
 * 人数覆写为逐字节非原子操作，跨线程极端情况下可能读到撕裂的数字，仅影响显示且瞬时，可接受。</p>
 */
public final class ResponseTemplate {

    private static final int COUNT_WIDTH = 8;
    private static final int PROTOCOL_WIDTH = 9;
    private static final int MAX_VIEWS = 64;
    /** 客户端列表最多展示的玩家行数。 */
    public static final int MAX_SAMPLE_ROWS = 10;

    /** 动态模板（文本含 {online}/{max} 占位符或玩家列表跟随真实玩家）的整体重建输入。 */
    public record DynamicSource(
            String versionName,
            String descriptionWithPlaceholders,
            List<String> playerList,
            String faviconUrl,
            boolean realPlayers,
            TemplateFactory factory
    ) {
    }

    // 动态模板整体重建时会随新帧更新；静态模板构造后不变
    private int protocolOffset;
    private int onlineOffset;
    private int maxOffset;
    private final boolean fixedProtocol;
    private final boolean dynamic;
    private final DynamicSource dynamicSource;

    private volatile ByteBuf fullFrame;
    private final Map<Integer, ByteBuf> protocolViews = new ConcurrentHashMap<>();
    private volatile ServerPing compatPing;

    private ResponseTemplate(ByteBuf frame, Offsets offsets, boolean fixedProtocol, DynamicSource source) {
        this.fullFrame = frame;
        // 渲染期偏移相对 JSON 起点，帧内绝对偏移需加上帧前缀、包号与 JSON 长度前缀
        this.protocolOffset = offsets.protocol + offsets.jsonOffset;
        this.onlineOffset = offsets.online + offsets.jsonOffset;
        this.maxOffset = offsets.max + offsets.jsonOffset;
        this.fixedProtocol = fixedProtocol;
        this.dynamic = source != null;
        this.dynamicSource = source;
    }

    static ResponseTemplate compile(String json, Offsets offsets, boolean fixedProtocol,
                                    ServerPing compatPing, DynamicSource source) {
        ResponseTemplate template = new ResponseTemplate(toFrame(json, offsets), offsets, fixedProtocol, source);
        template.compatPing = compatPing;
        return template;
    }

    static Offsets newOffsets() {
        return new Offsets();
    }

    /**
     * 取一条可直接写出的完整响应帧（自带 VarInt 长度前缀）。
     * 调用方写出后必须释放（引用计数已加一）。模板已销毁时返回 null。
     *
     * <p>出站管线不会经过帧编码器（处理器挂在 pipeline 前段，outbound 向 head 传播），
     * 因此无论直写还是走 writeAndFlush，都必须使用完整帧。</p>
     *
     * @param protocol 客户端协议号
     */
    public ByteBuf acquire(int protocol) {
        ByteBuf frame = this.fullFrame;
        if (frame == null) {
            return null;
        }

        ByteBuf source;
        if (this.fixedProtocol) {
            source = frame;
        } else {
            ByteBuf view = this.protocolViews.get(protocol);
            if (view == null) {
                if (this.protocolViews.size() >= MAX_VIEWS) {
                    // 协议视图超上限（疑似扫描流量），退化为不带协议改写的共享模板
                    source = frame;
                } else {
                    source = this.protocolViews.computeIfAbsent(protocol, key -> makeView(frame, key));
                }
            } else {
                source = view;
            }
        }
        return source.retainedDuplicate();
    }

    /** 事件模式兜底用的 ServerPing（协议号仍需调用方按客户端替换）。 */
    public ServerPing acquireCompat() {
        return this.compatPing;
    }

    /** 事件模式下按客户端协议号替换版本信息。 */
    public ServerPing acquireCompat(int protocol) {
        ServerPing base = this.compatPing;
        if (base == null) {
            return null;
        }
        ServerPing.Version version = base.getVersion();
        if (this.fixedProtocol || version.getProtocol() == protocol) {
            return base;
        }
        return base.asBuilder().version(new ServerPing.Version(protocol, version.getName())).build();
    }

    /**
     * 人数变化：静态模板原位覆写数字；动态模板整体重建。
     * 所有已生成的协议视图同步覆写，保证任何协议视图都显示最新人数。
     */
    public void update(int online, int max) {
        if (this.dynamic) {
            rebuild(online, max);
            return;
        }

        ByteBuf frame = this.fullFrame;
        if (frame == null) {
            return;
        }
        writePaddedInt(frame, this.onlineOffset, COUNT_WIDTH, online);
        writePaddedInt(frame, this.maxOffset, COUNT_WIDTH, max);
        for (ByteBuf view : this.protocolViews.values()) {
            writePaddedInt(view, this.onlineOffset, COUNT_WIDTH, online);
            writePaddedInt(view, this.maxOffset, COUNT_WIDTH, max);
        }
        ServerPing base = this.compatPing;
        if (base != null) {
            this.compatPing = base.asBuilder()
                    .onlinePlayers(online)
                    .maximumPlayers(max)
                    .build();
        }
    }

    private void rebuild(int online, int max) {
        Offsets offsets = newOffsets();
        String json = this.dynamicSource.factory().renderDynamic(
                this.dynamicSource, online, max, this.placeholderProtocol(), offsets);
        ByteBuf frame = toFrame(json, offsets);

        ByteBuf oldFrame = this.fullFrame;
        this.fullFrame = frame;
        this.protocolOffset = offsets.protocol + offsets.jsonOffset;
        this.onlineOffset = offsets.online + offsets.jsonOffset;
        this.maxOffset = offsets.max + offsets.jsonOffset;
        // 事件模式兜底同步重渲染：描述内嵌的占位符文本需要跟随最新人数
        this.compatPing = this.dynamicSource.factory().rebuildCompat(this.dynamicSource, online, max);
        // 协议视图基于旧内存，全部作废，下次 ping 按需重建
        this.protocolViews.values().forEach(ByteBuf::release);
        this.protocolViews.clear();
        oldFrame.release();
    }

    private ByteBuf makeView(ByteBuf frame, int protocol) {
        ByteBuf view = frame.copy();
        writePaddedInt(view, this.protocolOffset, PROTOCOL_WIDTH, protocol);
        return view;
    }

    private int placeholderProtocol() {
        return this.fixedProtocol ? 1 : 0;
    }

    private static ByteBuf toFrame(String json, Offsets offsets) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int bodyLength = 1 + varIntBytes(bytes.length) + bytes.length;
        int frameLength = varIntBytes(bodyLength) + bodyLength;
        // 帧布局：[varint bodyLen][packet id][varint jsonLen][json]
        offsets.jsonOffset = (frameLength - bodyLength) + 1 + varIntBytes(bytes.length);
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
        this.protocolViews.values().forEach(ByteBuf::release);
        this.protocolViews.clear();
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
    }
}
