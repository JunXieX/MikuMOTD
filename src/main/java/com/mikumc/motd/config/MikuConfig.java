package com.mikumc.motd.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * HOCON 配置。缺失节点按内置默认值补全并回写，首次启动即生成带注释的完整配置。
 *
 * <p>功能面刻意精简：logo、MOTD 文本（MiniMessage）、人数上限（缺省读代理原生
 * show-max-players）、真实玩家列表（恒开，语义与原生代理一致）。</p>
 */
public final class MikuConfig {

    private String logo = "server-icon.png";
    private String motd = "<bold><gradient:#40c4ff:#a78bfa>MikuMOTD</gradient></bold>{NL}"
            + "<gray>由</gray> <aqua>MikuMC</aqua> <gray>驱动</gray>";
    private int maxPlayers = 0;
    private long updateIntervalMs = 1000L;
    private boolean directWrite = true;
    private boolean compatMode = false;
    private boolean logPings = false;
    private boolean logImproperPings = false;
    private boolean allowImproperPings = false;

    public String logo() {
        return this.logo;
    }

    public String motd() {
        return this.motd;
    }

    /** 人数上限覆盖；0 表示沿用 velocity.toml 的 show-max-players。 */
    public int maxPlayers() {
        return this.maxPlayers;
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
        this.logo = root.node("logo").getString(this.logo);
        this.motd = root.node("motd").getString(this.motd);
        this.maxPlayers = clampInt(root.node("max-players").getInt(0), 0, 99_999_999);
        this.updateIntervalMs = clamp(root.node("update-interval-ms").getLong(1000L), 100L, 3_600_000L);
        this.directWrite = root.node("direct-write").getBoolean(true);
        this.compatMode = root.node("compat-mode").getBoolean(false);
        this.logPings = root.node("log-pings").getBoolean(false);
        this.logImproperPings = root.node("log-improper-pings").getBoolean(false);
        this.allowImproperPings = root.node("allow-improper-pings").getBoolean(false);
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
        root.node("logo").set(this.logo).comment(
                "服务器图标：PNG 文件路径（相对插件目录或绝对路径）、data:image/png;base64,... 或 \"none\"");
        root.node("motd").set(this.motd).comment(
                "MOTD 文本（MiniMessage），{NL} 为换行");
        root.node("max-players").set(this.maxPlayers).comment(
                "显示的人数上限；0 = 使用 velocity.toml 的 show-max-players");
        root.node("update-interval-ms").set(this.updateIntervalMs).comment(
                "在线人数与真实玩家列表的刷新间隔（毫秒）");
        root.node("direct-write").set(this.directWrite).comment(
                "true 时直接写入连接出站缓冲（最快）；\n"
                + "false 时走 Netty 标准 write 路径，出站方向的其他处理器仍会看到响应帧。\n"
                + "两种模式的响应帧均自带长度前缀，仅传播路径不同");
        root.node("compat-mode").set(this.compatMode).comment(
                "强制使用事件模式（禁用快速路径注入）；\n"
                + "注入不可用时插件也会自动回退到事件模式，仅性能不同");
        root.node("log-pings").set(this.logPings).comment("在控制台记录每次 ping");
        root.node("log-improper-pings").set(this.logImproperPings).comment("记录乱序的异常 ping 序列");
        root.node("allow-improper-pings").set(this.allowImproperPings).comment(
                "允许非标准的 ping 顺序（不推荐：开放空 ping 攻击面）");
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
