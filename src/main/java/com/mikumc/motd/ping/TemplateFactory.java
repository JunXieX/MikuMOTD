package com.mikumc.motd.ping;

import com.mikumc.motd.config.ProfileData;
import com.mikumc.motd.config.TextFormat;
import com.mikumc.motd.ping.ResponseTemplate.DynamicSource;
import com.mikumc.motd.ping.ResponseTemplate.Offsets;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

/**
 * 配置文本到 {@link ResponseTemplate} 的渲染工厂。
 * 全部渲染（文本反序列化、JSON 拼装、图标加载）只发生在配置（重）加载阶段；
 * 玩家列表跟随真实玩家的模板在人数刷新周期内整体重建（低频）。
 */
public final class TemplateFactory {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    /** 玩家列表行：JSON 内的 id 与 name（name 已含旧版段落码或为玩家原始 ID 文本）。 */
    public record SampleEntry(String id, String name) {
    }

    private final Path dataDirectory;
    private final TextFormat format;
    private final double pngQuality;
    private final Logger logger;
    private final ProxyServer proxy;

    public TemplateFactory(Path dataDirectory, TextFormat format, double pngQuality,
                           Logger logger, ProxyServer proxy) {
        this.dataDirectory = dataDirectory;
        this.format = format;
        this.pngQuality = pngQuality;
        this.logger = logger;
        this.proxy = proxy;
    }

    /**
     * 由一个画像配置编译出模板数组（描述 × 图标 笛卡尔积，玩家列表共享）。
     * 任一文本含 {online}/{max} 占位符或玩家列表跟随真实玩家时生成动态模板。
     */
    public ResponseTemplate[] compile(ProfileData profile, boolean fixedProtocol) {
        List<String> descriptions = profile.descriptions().isEmpty() ? List.of("") : profile.descriptions();
        List<String> faviconUrls = resolveFavicons(profile.favicons());

        boolean dynamic = isDynamic(profile);
        ResponseTemplate[] templates = new ResponseTemplate[descriptions.size() * faviconUrls.size()];
        int index = 0;
        for (String description : descriptions) {
            for (String faviconUrl : faviconUrls) {
                templates[index++] = dynamic
                        ? compileDynamic(profile, description, faviconUrl, fixedProtocol)
                        : compileStatic(profile, description, faviconUrl, fixedProtocol);
            }
        }
        return templates;
    }

    private ResponseTemplate compileStatic(ProfileData profile, String description, String faviconUrl,
                                           boolean fixedProtocol) {
        String descriptionJson = GSON.serialize(this.format.deserialize(clean(description)));
        List<SampleEntry> rows = staticSampleRows(profile.playerList());
        String escapedVersion = ResponseTemplate.escapeJson(clean(profile.versionName()));

        Offsets offsets = ResponseTemplate.newOffsets();
        int placeholderProtocol = fixedProtocol ? 1 : 0;
        String json = renderJson(escapedVersion, descriptionJson, faviconUrl, rows,
                0, 1, placeholderProtocol, offsets);

        ServerPing compat = buildCompat(descriptionJson, rows, faviconUrl,
                clean(profile.versionName()), 0, 1, placeholderProtocol);
        return ResponseTemplate.compile(json, offsets, fixedProtocol, compat, null);
    }

    private ResponseTemplate compileDynamic(ProfileData profile, String description,
                                            String faviconUrl, boolean fixedProtocol) {
        String descriptionJson = GSON.serialize(this.format.deserialize(clean(description)));
        List<SampleEntry> rows = profile.realPlayers()
                ? realSampleRows()
                : staticSampleRows(profile.playerList());
        String escapedVersion = ResponseTemplate.escapeJson(clean(profile.versionName()));

        Offsets offsets = ResponseTemplate.newOffsets();
        String json = renderJson(escapedVersion, descriptionJson, faviconUrl, rows, 0, 1, 0, offsets);

        ServerPing compat = buildCompat(descriptionJson, rows, faviconUrl,
                clean(profile.versionName()), 0, 1, 0);
        DynamicSource source = new DynamicSource(profile.versionName(), description,
                profile.playerList(), faviconUrl, profile.realPlayers(), this);
        return ResponseTemplate.compile(json, offsets, false, compat, source);
    }

    /** 动态模板重建入口（由 {@link ResponseTemplate#update} 触发，低频）。 */
    String renderDynamic(DynamicSource source, int online, int max, int placeholderProtocol, Offsets offsets) {
        String descriptionJson = GSON.serialize(
                this.format.deserialize(clean(source.descriptionWithPlaceholders())
                        .replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max))));
        List<SampleEntry> rows = source.realPlayers()
                ? realSampleRows()
                : escapeRows(source.playerList(), online, max);
        String escapedVersion = ResponseTemplate.escapeJson(clean(source.versionName()));
        return renderJson(escapedVersion, descriptionJson, source.faviconUrl(), rows,
                online, max, placeholderProtocol, offsets);
    }

    /** 动态模板重建时同步重建事件模式兜底用的 ServerPing（低频）。 */
    ServerPing rebuildCompat(DynamicSource source, int online, int max) {
        String descriptionJson = GSON.serialize(
                this.format.deserialize(clean(source.descriptionWithPlaceholders())
                        .replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max))));
        List<SampleEntry> rows = source.realPlayers()
                ? realSampleRows()
                : escapeRows(source.playerList(), online, max);
        return buildCompat(descriptionJson, rows, source.faviconUrl(),
                clean(source.versionName()), online, max, 0);
    }

    private String renderJson(String escapedVersion, String descriptionJson, String faviconUrl,
                              List<SampleEntry> rows, int online, int max,
                              int placeholderProtocol, Offsets offsets) {
        // 偏移按 char 记录，toFrame 会统一换算为 UTF-8 字节偏移（槽位可位于多字节内容之后）
        StringBuilder sb = new StringBuilder(1024 + descriptionJson.length());
        sb.append("{\"players\":{\"online\":");
        offsets.online = sb.length();
        sb.append("        ");
        sb.append(",\"max\":");
        offsets.max = sb.length();
        sb.append("        ");
        sb.append(",\"sample\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SampleEntry row = rows.get(i);
            sb.append("{\"id\":\"").append(ResponseTemplate.escapeJson(row.id())).append("\",\"name\":\"")
                    .append(ResponseTemplate.escapeJson(row.name())).append("\"}");
        }
        sb.append("]},\"version\":{\"protocol\":");
        offsets.protocol = sb.length();
        sb.append("         ");
        sb.append(",\"name\":\"").append(escapedVersion).append("\"},\"description\":")
                .append(descriptionJson);
        if (faviconUrl != null) {
            sb.append(",\"favicon\":\"").append(faviconUrl).append('"');
        }
        sb.append('}');

        ResponseTemplate.writeAsciiNumber(sb, offsets.online, 8, Math.max(0, online));
        ResponseTemplate.writeAsciiNumber(sb, offsets.max, 8, Math.max(0, max));
        ResponseTemplate.writeAsciiNumber(sb, offsets.protocol, 9, Math.max(0, placeholderProtocol));
        return sb.toString();
    }

    private ServerPing buildCompat(String descriptionJson, List<SampleEntry> rows, String faviconUrl,
                                   String versionName, int online, int max, int placeholderProtocol) {
        ServerPing.Builder builder = ServerPing.builder()
                .version(new ServerPing.Version(placeholderProtocol, versionName))
                .description(GSON.deserialize(descriptionJson))
                .onlinePlayers(Math.max(0, online))
                .maximumPlayers(Math.max(0, max));
        if (faviconUrl != null) {
            builder.favicon(new Favicon(faviconUrl));
        }
        if (!rows.isEmpty()) {
            ServerPing.SamplePlayer[] players = rows.stream()
                    .map(row -> {
                        try {
                            return new ServerPing.SamplePlayer(
                                    row.name(), UUID.fromString(row.id()));
                        } catch (IllegalArgumentException e) {
                            return new ServerPing.SamplePlayer(row.name(), UUID.randomUUID());
                        }
                    })
                    .toArray(ServerPing.SamplePlayer[]::new);
            builder.samplePlayers(players);
        }
        return builder.build();
    }

    /** 静态玩家列表行：配置文本按输入格式渲染为旧版段落码 + 随机 UUID。 */
    private List<SampleEntry> staticSampleRows(List<String> playerList) {
        List<String> rows = playerList.stream()
                .limit(ResponseTemplate.MAX_SAMPLE_ROWS)
                .toList();
        List<SampleEntry> entries = new ArrayList<>(rows.size());
        for (String row : rows) {
            entries.add(new SampleEntry(
                    UUID.randomUUID().toString(),
                    LEGACY_SECTION.serialize(this.format.deserialize(clean(row)))));
        }
        return entries;
    }

    /** 真实在线玩家：原始 ID 文本与真实 UUID（低频刷新路径，取当前快照）。 */
    private List<SampleEntry> realSampleRows() {
        List<Player> online = new ArrayList<>(this.proxy.getAllPlayers());
        if (online.size() > ResponseTemplate.MAX_SAMPLE_ROWS) {
            online = online.subList(0, ResponseTemplate.MAX_SAMPLE_ROWS);
        }
        List<SampleEntry> entries = new ArrayList<>(online.size());
        for (Player player : online) {
            entries.add(new SampleEntry(
                    player.getUniqueId().toString(),
                    player.getUsername()));
        }
        return entries;
    }

    private List<SampleEntry> escapeRows(List<String> rows, int online, int max) {
        List<SampleEntry> escaped = new ArrayList<>(rows.size());
        for (String row : rows) {
            String rendered = clean(row);
            if (online != Integer.MIN_VALUE) {
                rendered = rendered.replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max));
            }
            escaped.add(new SampleEntry(
                    UUID.randomUUID().toString(),
                    LEGACY_SECTION.serialize(this.format.deserialize(rendered))));
        }
        return escaped;
    }

    private List<String> resolveFavicons(List<String> locations) {
        List<String> urls = new ArrayList<>(Math.max(1, locations.size()));
        if (locations.isEmpty()) {
            urls.add(null);
            return urls;
        }
        for (String location : locations) {
            try {
                urls.add(com.mikumc.motd.util.Favicon.load(location, this.dataDirectory, this.pngQuality));
            } catch (Exception e) {
                this.logger.warn("图标加载失败（已跳过）：{}", location, e);
            }
        }
        if (urls.isEmpty()) {
            urls.add(null);
        }
        return urls;
    }

    private static boolean isDynamic(ProfileData profile) {
        return profile.realPlayers()
                || containsPlaceholder(profile.versionName())
                || containsPlaceholder(String.join("", profile.descriptions()))
                || containsPlaceholder(String.join("", profile.playerList()));
    }

    private static boolean containsPlaceholder(String text) {
        return text.contains("{online}") || text.contains("{max}");
    }

    private static String clean(String text) {
        return text.replace("{NL}", "\n");
    }
}
