package com.mikumc.motd.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * HOCON 配置。缺失节点按内置默认值补全并回写，首次启动即生成带注释的完整配置。
 */
public final class MikuConfig {

    private long updateIntervalMs = 3000L;
    private boolean directWrite = true;
    private boolean compatMode = false;
    private boolean logPings = false;
    private boolean logImproperPings = false;
    private boolean allowImproperPings = false;
    private TextFormat textFormat = TextFormat.MINIMESSAGE;
    private double pngQuality = 0.0D;

    private MaxCountType maxCountType = MaxCountType.FIXED;
    private int maxCount = 1000;
    private int fakeOnlineFixed = 0;
    private int fakeOnlinePercent = 0;

    private ProfileData defaultProfile = new ProfileData(
            "MikuMOTD",
            List.of("<bold><gradient:#40c4ff:#a78bfa>MikuMOTD</gradient></bold> <gray>»</gray> <yellow>极速 MOTD"),
            List.of("server-icon.png"),
            List.of("<gray>由</gray> <aqua>MikuMC</aqua> <gray>驱动</gray>", "<yellow>交流群 <white>1105054380")
    );
    private Map<String, ProfileData> protocolProfiles = Map.of();
    private Map<String, ProfileData> domainProfiles = Map.of();

    private boolean maintenanceEnabled = false;
    private boolean maintenanceShowRealVersion = true;
    private boolean maintenanceKickOnJoin = false;
    private List<InetAddress> kickWhitelist = List.of(localhost());
    private String kickMessage = "<red>服务器维护中，请稍后再来</red>";
    private int maintenanceOverrideOnline = -1;
    private int maintenanceOverrideMaxOnline = -1;
    private ProfileData maintenanceProfile = new ProfileData(
            "维护中",
            List.of("<bold><red>服务器维护中</red></bold>{NL}<gray>请稍后再连接</gray>"),
            List.of("server-icon.png"),
            List.of("<red>维护模式已开启</red>")
    );
    private Map<String, ProfileData> maintenanceProtocolProfiles = Map.of();
    private Map<String, ProfileData> maintenanceDomainProfiles = Map.of();

    public enum MaxCountType {
        FIXED,
        ADD
    }

    public long updateIntervalMs() {
        return this.updateIntervalMs;
    }

    public boolean directWrite() {
        return this.directWrite;
    }

    public boolean compatMode() {
        return this.compatMode;
    }

    public boolean logPings() {
        return this.logPings;
    }

    public boolean logImproperPings() {
        return this.logImproperPings;
    }

    public boolean allowImproperPings() {
        return this.allowImproperPings;
    }

    public TextFormat textFormat() {
        return this.textFormat;
    }

    public double pngQuality() {
        return this.pngQuality;
    }

    public MaxCountType maxCountType() {
        return this.maxCountType;
    }

    public int maxCount() {
        return this.maxCount;
    }

    public int fakeOnlineFixed() {
        return this.fakeOnlineFixed;
    }

    public int fakeOnlinePercent() {
        return this.fakeOnlinePercent;
    }

    public ProfileData defaultProfile() {
        return this.defaultProfile;
    }

    public Map<String, ProfileData> protocolProfiles() {
        return this.protocolProfiles;
    }

    public Map<String, ProfileData> domainProfiles() {
        return this.domainProfiles;
    }

    public boolean maintenanceEnabled() {
        return this.maintenanceEnabled;
    }

    public void setMaintenanceEnabled(boolean enabled) {
        this.maintenanceEnabled = enabled;
    }

    public boolean maintenanceShowRealVersion() {
        return this.maintenanceShowRealVersion;
    }

    public boolean maintenanceKickOnJoin() {
        return this.maintenanceKickOnJoin;
    }

    public List<InetAddress> kickWhitelist() {
        return this.kickWhitelist;
    }

    public String kickMessage() {
        return this.kickMessage;
    }

    public int maintenanceOverrideOnline() {
        return this.maintenanceOverrideOnline;
    }

    public int maintenanceOverrideMaxOnline() {
        return this.maintenanceOverrideMaxOnline;
    }

    public ProfileData maintenanceProfile() {
        return this.maintenanceProfile;
    }

    public Map<String, ProfileData> maintenanceProtocolProfiles() {
        return this.maintenanceProtocolProfiles;
    }

    public Map<String, ProfileData> maintenanceDomainProfiles() {
        return this.maintenanceDomainProfiles;
    }

    public void load(Path file) throws IOException {
        boolean fresh = !Files.exists(file);
        if (fresh) {
            Files.createDirectories(file.getParent());
        }

        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(file)
                .build();
        CommentedConfigurationNode root = loader.load();
        this.read(root);
        if (fresh) {
            this.write(root);
            loader.save(root);
        } else if (this.fillMissingDefaults(root)) {
            // 补全新版本新增的配置项，保留用户已有节点与注释
            loader.save(root);
        }
    }

    private void read(CommentedConfigurationNode root) throws IOException {
        CommentedConfigurationNode general = root.node("general");
        this.updateIntervalMs = clamp(general.node("update-interval-ms").getLong(3000L), 100L, 3_600_000L);
        this.directWrite = general.node("direct-write").getBoolean(true);
        this.compatMode = general.node("compat-mode").getBoolean(false);
        this.logPings = general.node("log-pings").getBoolean(false);
        this.logImproperPings = general.node("log-improper-pings").getBoolean(false);
        this.allowImproperPings = general.node("allow-improper-pings").getBoolean(false);
        this.textFormat = TextFormat.parse(general.node("text-format").getString("MINIMESSAGE"));
        this.pngQuality = clamp(general.node("png-quality").getDouble(0.0D), -1.0D, 1.0D);

        CommentedConfigurationNode players = root.node("players");
        String type = players.node("max-count-type").getString("FIXED");
        this.maxCountType = "ADD".equalsIgnoreCase(type) ? MaxCountType.ADD : MaxCountType.FIXED;
        this.maxCount = clamp(players.node("max-count").getInt(1000), 0, 99_999_999);
        this.fakeOnlineFixed = clamp(players.node("fake-online-fixed").getInt(0), 0, 99_999_999);
        this.fakeOnlinePercent = clamp(players.node("fake-online-percent").getInt(0), 0, 1_000_000);

        this.defaultProfile = readProfile(root.node("motd"), this.defaultProfile);
        this.protocolProfiles = readProfileMap(root.node("protocol-motd"));
        this.domainProfiles = readProfileMap(root.node("domain-motd"));

        CommentedConfigurationNode maintenance = root.node("maintenance");
        this.maintenanceEnabled = maintenance.node("enabled").getBoolean(false);
        this.maintenanceShowRealVersion = maintenance.node("show-real-version").getBoolean(true);
        this.maintenanceKickOnJoin = maintenance.node("kick-on-join").getBoolean(false);
        this.kickWhitelist = readWhitelist(maintenance.node("kick-whitelist"));
        this.kickMessage = maintenance.node("kick-message").getString(this.kickMessage);
        this.maintenanceOverrideOnline = clamp(
                maintenance.node("override-online").getInt(-1), -1, 99_999_999);
        this.maintenanceOverrideMaxOnline = clamp(
                maintenance.node("override-max-online").getInt(-1), -1, 99_999_999);
        this.maintenanceProfile = readProfile(maintenance.node("motd"), this.maintenanceProfile);
        this.maintenanceProtocolProfiles = readProfileMap(maintenance.node("protocol-motd"));
        this.maintenanceDomainProfiles = readProfileMap(maintenance.node("domain-motd"));
    }

    private ProfileData readProfile(CommentedConfigurationNode node, ProfileData fallback) throws IOException {
        String versionName = node.node("version-name").getString(fallback.versionName());
        List<String> descriptions = readStringList(node.node("descriptions"), fallback.descriptions());
        List<String> favicons = readStringList(node.node("favicons"), fallback.favicons());
        List<String> playerList = readStringList(node.node("player-list"), fallback.playerList());
        return new ProfileData(versionName, descriptions, favicons, playerList);
    }

    private Map<String, ProfileData> readProfileMap(CommentedConfigurationNode node) throws IOException {
        Map<Object, ? extends CommentedConfigurationNode> children = node.childrenMap();
        if (children.isEmpty()) {
            return Map.of();
        }
        Map<String, ProfileData> result = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : children.entrySet()) {
            result.put(String.valueOf(entry.getKey()), readProfile(entry.getValue(), ProfileData.EMPTY));
        }
        return result;
    }

    private List<String> readStringList(CommentedConfigurationNode node, List<String> fallback) throws IOException {
        // 未配置（节点不存在）取默认值；显式配置为空列表（如无玩家列表）则尊重配置
        if (node.virtual()) {
            return fallback;
        }
        List<String> list = node.getList(String.class);
        return list == null ? fallback : list;
    }

    private List<InetAddress> readWhitelist(CommentedConfigurationNode node) throws IOException {
        List<String> raw = node.getList(String.class);
        if (raw == null || raw.isEmpty()) {
            return List.of(localhost());
        }
        List<InetAddress> addresses = new ArrayList<>();
        for (String entry : raw) {
            try {
                addresses.add(InetAddress.getByName(entry.trim()));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("无法解析维护白名单地址: " + entry, e);
            }
        }
        return List.copyOf(addresses);
    }

    /**
     * 向缺失的节点写默认值。返回是否有写入（决定是否需要回写文件）。
     */
    private boolean fillMissingDefaults(CommentedConfigurationNode root) throws IOException {
        CommentedConfigurationNode fresh = CommentedConfigurationNode.root();
        this.write(fresh);
        boolean[] changed = {false};
        copyMissing(fresh, root, changed);
        return changed[0];
    }

    private void copyMissing(CommentedConfigurationNode from, CommentedConfigurationNode to, boolean[] changed) {
        from.childrenMap().forEach((key, child) -> {
            CommentedConfigurationNode target = to.node(key);
            if (target.virtual()) {
                target.from(child);
                target.comment(child.comment());
                changed[0] = true;
            } else if (child.isMap()) {
                copyMissing(child, target, changed);
            }
        });
    }

    private void write(CommentedConfigurationNode root) throws IOException {
        CommentedConfigurationNode general = root.node("general");
        general.node("update-interval-ms").set(this.updateIntervalMs).comment(
                "在线人数刷新间隔（毫秒）");
        general.node("direct-write").set(this.directWrite).comment(
                "true 时直接写入连接出站缓冲，完全绕过 Netty 出站管线（最快）；\n"
                + "false 时走标准管线写出，兼容在出站方向拦截数据包的其他插件");
        general.node("compat-mode").set(this.compatMode).comment(
                "强制使用事件模式（禁用快速路径注入）；\n"
                + "注入不可用时插件也会自动回退到事件模式，仅性能不同");
        general.node("log-pings").set(this.logPings).comment("在控制台记录每次 ping");
        general.node("log-improper-pings").set(this.logImproperPings).comment("记录乱序的异常 ping 序列");
        general.node("allow-improper-pings").set(this.allowImproperPings).comment(
                "允许非标准的 ping 顺序（不推荐：开放空 ping 攻击面）");
        general.node("text-format").set(this.textFormat.name()).comment(
                "MOTD 文本格式：MINIMESSAGE / LEGACY_AMPERSAND / LEGACY_SECTION / JSON");
        general.node("png-quality").set(this.pngQuality).comment(
                "图标重编码质量 0.0~1.0，越小文件越小；-1 关闭重编码");

        CommentedConfigurationNode players = root.node("players");
        players.node("max-count-type").set(this.maxCountType.name()).comment(
                "FIXED：最大人数固定为 max-count；ADD：最大人数 = 当前人数 + max-count");
        players.node("max-count").set(this.maxCount);
        players.node("fake-online-fixed").set(this.fakeOnlineFixed).comment(
                "假人数固定加值");
        players.node("fake-online-percent").set(this.fakeOnlinePercent).comment(
                "假人数百分比加值，基于（真实人数+固定加值）");

        writeProfile(root.node("motd"), this.defaultProfile,
                "默认 MOTD。描述/玩家列表支持占位符 {online} {max} 与换行符 {NL}\n"
                + "另有可选节 protocol-motd（按协议段，key 如 \"757-800\"）与 domain-motd（按域名，key 如 \"play.example.com:25565\"），\n"
                + "节点结构与本节相同，域名匹配不区分大小写");
        writeProfileMap(root.node("protocol-motd"), this.protocolProfiles,
                "按协议版本段的 MOTD，key 为段（如 \"757-800\"）或单值（如 \"761\"），节点同 motd");
        writeProfileMap(root.node("domain-motd"), this.domainProfiles,
                "按域名的 MOTD，key 为 \"域名:端口\"，节点同 motd；域名匹配不区分大小写");

        CommentedConfigurationNode maintenance = root.node("maintenance");
        maintenance.node("enabled").set(this.maintenanceEnabled).comment("维护模式总开关（可在游戏内切换）");
        maintenance.node("show-real-version").set(this.maintenanceShowRealVersion).comment(
                "维护模式下是否在版本号中返回客户端真实协议（false 时客户端显示不可加入）");
        maintenance.node("kick-on-join").set(this.maintenanceKickOnJoin).comment(
                "维护模式下是否在玩家尝试登录时直接拒绝");
        maintenance.node("kick-whitelist").setList(
                        String.class, this.kickWhitelist.stream().map(InetAddress::getHostAddress).toList())
                .comment("维护踢出白名单（IP 地址，始终放行）");
        maintenance.node("kick-message").set(this.kickMessage).comment(
                "维护踢出消息，支持占位符与 {NL}");
        maintenance.node("override-online").set(this.maintenanceOverrideOnline).comment(
                "维护模式下覆盖显示的在线人数，-1 不覆盖");
        maintenance.node("override-max-online").set(this.maintenanceOverrideMaxOnline).comment(
                "维护模式下覆盖显示的最大人数，-1 不覆盖");
        writeProfile(maintenance.node("motd"), this.maintenanceProfile, "维护模式 MOTD");
        writeProfileMap(maintenance.node("protocol-motd"), this.maintenanceProtocolProfiles,
                "维护模式按协议版本的 MOTD，节点同 motd");
        writeProfileMap(maintenance.node("domain-motd"), this.maintenanceDomainProfiles,
                "维护模式按域名的 MOTD，节点同 motd");
    }

    private void writeProfile(CommentedConfigurationNode node, ProfileData profile, String comment) throws IOException {
        node.comment(comment);
        node.node("version-name").set(profile.versionName());
        node.node("descriptions").setList(String.class, profile.descriptions());
        node.node("favicons").setList(String.class, profile.favicons());
        node.node("player-list").setList(String.class, profile.playerList());
    }

    private void writeProfileMap(CommentedConfigurationNode node, Map<String, ProfileData> profiles, String comment) throws IOException {
        // 空映射时不写出节点：写出空对象会被序列化为 "key=null"，
        // 用户之后手动添加同名对象节会被 HOCON 的 null 值覆盖
        if (profiles.isEmpty()) {
            return;
        }
        node.comment(comment);
        for (Map.Entry<String, ProfileData> entry : profiles.entrySet()) {
            writeProfile(node.node(entry.getKey()), entry.getValue(), null);
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static InetAddress localhost() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }
}
