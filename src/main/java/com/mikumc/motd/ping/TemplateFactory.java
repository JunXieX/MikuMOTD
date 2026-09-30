package com.mikumc.motd.ping;

import com.mikumc.motd.config.ProfileData;
import com.mikumc.motd.config.TextFormat;
import com.mikumc.motd.ping.ResponseTemplate.DynamicSource;
import com.mikumc.motd.ping.ResponseTemplate.Offsets;
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
 * 全部渲染（文本反序列化、JSON 拼装、图标加载）只发生在配置（重）加载阶段。
 */
public final class TemplateFactory {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private final Path dataDirectory;
    private final TextFormat format;
    private final double pngQuality;
    private final Logger logger;

    public TemplateFactory(Path dataDirectory, TextFormat format, double pngQuality, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.format = format;
        this.pngQuality = pngQuality;
        this.logger = logger;
    }

    /**
     * 由一个画像配置编译出模板数组（描述 × 图标 笛卡尔积，玩家列表共享）。
     * 任一文本含 {online}/{max} 占位符时生成动态模板。
     */
    public ResponseTemplate[] compile(ProfileData profile, boolean fixedProtocol) {
        List<String> descriptions = profile.descriptions().isEmpty() ? List.of("") : profile.descriptions();
        List<String> faviconUrls = resolveFavicons(profile.favicons());
        List<String> sampleRows = profile.playerList().stream()
                .limit(ResponseTemplate.MAX_SAMPLE_ROWS)
                .toList();

        boolean dynamic = isDynamic(profile);
        ResponseTemplate[] templates = new ResponseTemplate[descriptions.size() * faviconUrls.size()];
        int index = 0;
        for (String description : descriptions) {
            for (String faviconUrl : faviconUrls) {
                templates[index++] = dynamic
                        ? compileDynamic(profile.versionName(), description, faviconUrl, sampleRows)
                        : compileStatic(profile.versionName(), description, faviconUrl, sampleRows, fixedProtocol);
            }
        }
        return templates;
    }

    private ResponseTemplate compileStatic(String versionName, String description, String faviconUrl,
                                           List<String> sampleRows, boolean fixedProtocol) {
        String descriptionJson = GSON.serialize(this.format.deserialize(clean(description)));
        List<String> escapedRows = escapeRows(sampleRows);
        String escapedVersion = ResponseTemplate.escapeJson(clean(versionName));

        Offsets offsets = ResponseTemplate.newOffsets();
        int placeholderProtocol = fixedProtocol ? 1 : 0;
        String json = renderJson(escapedVersion, descriptionJson, faviconUrl, escapedRows,
                0, 1, placeholderProtocol, offsets);

        ServerPing compat = buildCompat(descriptionJson, escapedRows, faviconUrl, versionName, 0, 1, placeholderProtocol);
        return ResponseTemplate.compile(json, offsets, fixedProtocol, compat, null);
    }

    private ResponseTemplate compileDynamic(String versionName, String description,
                                            String faviconUrl, List<String> sampleRows) {
        String descriptionJson = GSON.serialize(this.format.deserialize(clean(description)));
        List<String> escapedRows = escapeRows(sampleRows);
        String escapedVersion = ResponseTemplate.escapeJson(clean(versionName));

        Offsets offsets = ResponseTemplate.newOffsets();
        String json = renderJson(escapedVersion, descriptionJson, faviconUrl, escapedRows,
                0, 1, 0, offsets);

        ServerPing compat = buildCompat(descriptionJson, escapedRows, faviconUrl, versionName, 0, 1, 0);
        DynamicSource source = new DynamicSource(versionName, description, sampleRows, faviconUrl, this);
        return ResponseTemplate.compile(json, offsets, false, compat, source);
    }

    /** 动态模板重建入口（由 {@link ResponseTemplate#update} 触发，低频）。 */
    String renderDynamic(DynamicSource source, int online, int max, int placeholderProtocol, Offsets offsets) {
        String descriptionJson = GSON.serialize(
                this.format.deserialize(clean(source.descriptionWithPlaceholders())
                        .replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max))));
        List<String> escapedRows = escapeRows(source.playerList(), online, max);
        String escapedVersion = ResponseTemplate.escapeJson(clean(source.versionName()));
        return renderJson(escapedVersion, descriptionJson, source.faviconUrl(), escapedRows,
                online, max, placeholderProtocol, offsets);
    }

    /** 动态模板重建时同步重建事件模式兜底用的 ServerPing（低频）。 */
    ServerPing rebuildCompat(DynamicSource source, int online, int max) {
        String descriptionJson = GSON.serialize(
                this.format.deserialize(clean(source.descriptionWithPlaceholders())
                        .replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max))));
        List<String> escapedRows = escapeRows(source.playerList(), online, max);
        return buildCompat(descriptionJson, escapedRows, source.faviconUrl(),
                clean(source.versionName()), online, max, 0);
    }

    private String renderJson(String escapedVersion, String descriptionJson, String faviconUrl,
                              List<String> escapedRows, int online, int max,
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
        for (int i = 0; i < escapedRows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"name\":\"")
                    .append(escapedRows.get(i)).append("\"}");
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

    private ServerPing buildCompat(String descriptionJson, List<String> escapedRows, String faviconUrl,
                                   String versionName, int online, int max, int placeholderProtocol) {
        ServerPing.Builder builder = ServerPing.builder()
                .version(new ServerPing.Version(placeholderProtocol, versionName))
                .description(GSON.deserialize(descriptionJson))
                .onlinePlayers(Math.max(0, online))
                .maximumPlayers(Math.max(0, max));
        if (faviconUrl != null) {
            builder.favicon(new Favicon(faviconUrl));
        }
        if (!escapedRows.isEmpty()) {
            ServerPing.SamplePlayer[] players = escapedRows.stream()
                    .map(row -> new ServerPing.SamplePlayer(row, UUID.randomUUID()))
                    .toArray(ServerPing.SamplePlayer[]::new);
            builder.samplePlayers(players);
        }
        return builder.build();
    }

    private List<String> escapeRows(List<String> rows) {
        return escapeRows(rows, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private List<String> escapeRows(List<String> rows, int online, int max) {
        List<String> escaped = new ArrayList<>(rows.size());
        for (String row : rows) {
            String rendered = clean(row);
            if (online != Integer.MIN_VALUE) {
                rendered = rendered.replace("{online}", String.valueOf(online))
                        .replace("{max}", String.valueOf(max));
            }
            escaped.add(ResponseTemplate.escapeJson(LEGACY_SECTION.serialize(this.format.deserialize(rendered))));
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
        return containsPlaceholder(profile.versionName())
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
