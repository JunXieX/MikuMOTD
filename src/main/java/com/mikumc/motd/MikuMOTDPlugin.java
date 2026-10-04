package com.mikumc.motd;

import com.google.inject.Inject;
import com.mikumc.motd.command.MikuCommand;
import com.mikumc.motd.compat.CompatListener;
import com.mikumc.motd.config.MikuConfig;
import com.mikumc.motd.inject.Injector;
import com.mikumc.motd.ping.ResponseTemplate;
import com.mikumc.motd.util.Favicon;
import com.mikumc.motd.util.VanishSupport;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;

/**
 * MikuMOTD：MikuMC 服务器的 Velocity 代理 MOTD 插件。
 *
 * <p>功能面：自定义 logo、MiniMessage MOTD 文本、人数上限覆盖（缺省读代理原生
 * show-max-players）、真实玩家列表。核心路径（状态查询）完全在 Netty 字节层完成：
 * 响应帧预渲染、零拷贝分发，不进入代理会话处理。</p>
 */
@Plugin(
        id = "mikumotd",
        name = "MikuMOTD",
        version = "1.6.0",
        description = "MikuMC 服务器原创插件，作者 JunXieX，交流群 1105054380",
        authors = {"JunXieX"}
)
public final class MikuMOTDPlugin {

    private static final long FAULT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory = Path.of("plugins", "MikuMOTD");
    private final AtomicLong lastFaultLog = new AtomicLong();
    private final Object reloadLock = new Object();

    private volatile MikuConfig config;
    private volatile ResponseTemplate template;
    private volatile boolean fastPath;
    private volatile ScheduledTask refreshTask;

    @Inject
    public MikuMOTDPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            this.reload();
        } catch (Exception e) {
            // 配置非法等也走这里；保底注册事件模式与命令，让 /mikumotd reload 可用
            this.logger.error("配置加载失败，修正 plugins/mikumotd/config.conf 后执行 /mikumotd reload", e);
        }

        if (this.config != null && !this.config.compatMode()) {
            if (Injector.inject(this, this.proxy) != null) {
                this.fastPath = true;
                this.logger.info("快速路径已启用（字节级状态响应）");
            }
        } else {
            this.logger.info("事件模式兜底已注册");
        }
        if (!this.fastPath) {
            this.proxy.getEventManager().register(this, new CompatListener(this));
        }

        CommandManager commands = this.proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("mikumotd")
                .plugin(this)
                .build();
        commands.register(meta, new MikuCommand(this));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.fastPath) {
            Injector.restore(this, this.proxy);
        }
        ScheduledTask task = this.refreshTask;
        if (task != null) {
            task.cancel();
        }
        ResponseTemplate current = this.template;
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * 加载配置并重建模板。失败时保留旧配置继续运行并抛出异常交由调用方报告。
     */
    public boolean reload() throws IOException {
        synchronized (this.reloadLock) {
            MikuConfig config = new MikuConfig();
            config.load(this.dataDirectory.resolve("config.conf"));

            String descriptionJson = GSON.serialize(
                    MiniMessage.miniMessage().deserialize(config.motd().replace("{NL}", "\n")));
            String faviconUrl;
            try {
                faviconUrl = Favicon.load(config.logo(), this.dataDirectory, -1.0D);
            } catch (Exception e) {
                // fail-open：图标缺失/损坏只降级为无图标，不阻断配置加载
                this.logger.warn("图标加载失败（本次将不含图标）：{}", config.logo(), e);
                faviconUrl = null;
            }
            ResponseTemplate fresh = ResponseTemplate.compile(
                    descriptionJson,
                    "MikuMOTD",
                    faviconUrl,
                    0,
                    this.visiblePlayerCount(),
                    this.maxPlayers(config),
                    this.currentSample());

            this.config = config;
            ResponseTemplate previous = this.template;
            this.template = fresh;
            if (previous != null) {
                previous.dispose();
            }

            this.rescheduleRefreshTask(config);
            this.logger.info("配置已加载（logo：{}，人数上限：{}）",
                    config.logo(), this.maxPlayers(config));
            return true;
        }
    }

    private void rescheduleRefreshTask(MikuConfig config) {
        ScheduledTask previous = this.refreshTask;
        if (previous != null) {
            previous.cancel();
        }
        this.refreshTask = this.proxy.getScheduler()
                .buildTask(this, this::refresh)
                .delay(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .repeat(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void refresh() {
        ResponseTemplate template = this.template;
        if (template == null) {
            return;
        }
        template.update(this.visiblePlayerCount(), this.maxPlayers(this.config), this.currentSample());
    }

    /** 人数上限：插件覆盖值优先，0 沿用代理原生 show-max-players。 */
    private int maxPlayers(MikuConfig config) {
        if (config == null) {
            return 0;
        }
        int override = config.maxPlayers();
        return override > 0 ? override : this.proxy.getConfiguration().getShowMaxPlayers();
    }

    /** 可见在线人数：安装了 MikuVanish 时剔除隐身玩家，否则为总在线数。 */
    private int visiblePlayerCount() {
        Set<UUID> vanished = VanishSupport.vanishedPlayers();
        if (vanished.isEmpty()) {
            return this.proxy.getPlayerCount();
        }
        return (int) this.proxy.getAllPlayers().stream()
                .filter(player -> !vanished.contains(player.getUniqueId()))
                .count();
    }

    /** 真实在线玩家快照：剔除隐身玩家，按 UUID 排序（与原生代理同为 12 行上限）。 */
    private List<ResponseTemplate.SampleEntry> currentSample() {
        Set<UUID> vanished = VanishSupport.vanishedPlayers();
        List<Player> online = new ArrayList<>(this.proxy.getAllPlayers());
        online.removeIf(player -> vanished.contains(player.getUniqueId()));
        online.sort(Comparator.comparing(Player::getUniqueId));
        if (online.size() > ResponseTemplate.MAX_SAMPLE_ROWS) {
            online = online.subList(0, ResponseTemplate.MAX_SAMPLE_ROWS);
        }
        List<ResponseTemplate.SampleEntry> entries = new ArrayList<>(online.size());
        for (Player player : online) {
            entries.add(new ResponseTemplate.SampleEntry(player.getUniqueId(), player.getUsername()));
        }
        return entries;
    }

    // ---------------------------------------------------------------------
    // 供快速路径（事件循环高频）调用的只读入口
    // ---------------------------------------------------------------------

    /** 每次状态请求选择响应帧；插件未就绪或重载竞态时返回 null（连接将被关闭）。 */
    public ByteBuf selectResponse(int protocol) {
        ResponseTemplate template = this.template;
        return template == null ? null : template.acquire(protocol);
    }

    /** 事件模式兜底取当前模板。 */
    public ResponseTemplate templateForCompat() {
        return this.template;
    }

    public boolean directWrite() {
        MikuConfig config = this.config;
        return config != null && config.directWrite();
    }

    public boolean allowImproperPings() {
        MikuConfig config = this.config;
        return config != null && config.allowImproperPings();
    }

    public void onStatusHandshake(java.net.SocketAddress remote, int protocol) {
        if (this.config != null && this.config.logPings()) {
            this.logger.info("{} 正在以协议 {} 进行状态查询", remote, protocol);
        }
    }

    public void logImproperPing(java.net.SocketAddress remote, String detail) {
        if (this.config != null && this.config.logImproperPings()) {
            this.logger.warn("{} 非标准 ping 序列：{}", remote, detail);
        }
    }

    /** 快速路径内部异常的限速日志（避免被畸形包刷屏）。 */
    public void logFastPathFault(java.net.SocketAddress remote, Exception cause) {
        long now = System.nanoTime();
        long last = this.lastFaultLog.get();
        if (now - last >= FAULT_LOG_INTERVAL_NANOS && this.lastFaultLog.compareAndSet(last, now)) {
            this.logger.warn("快速路径处理 {} 出现异常，连接已关闭（限速日志）", remote, cause);
        }
    }

    public Component statusInfo() {
        MikuConfig config = this.config;
        ResponseTemplate template = this.template;
        if (config == null || template == null) {
            return Component.text("MikuMOTD 尚未就绪", NamedTextColor.RED);
        }
        String mode = this.fastPath ? "快速路径（字节级）" : "事件模式（兜底）";
        return Component.text()
                .append(Component.text("MikuMOTD", TextColor.color(0x40c4ff)))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("模式：" + mode, NamedTextColor.GREEN))
                .append(Component.text(" ｜ 人数上限：" + this.maxPlayers(config), NamedTextColor.AQUA))
                .append(Component.text(" ｜ 在线：" + this.visiblePlayerCount(), NamedTextColor.AQUA))
                .append(Component.text(" ｜ 刷新间隔：" + config.updateIntervalMs() + "ms",
                        NamedTextColor.GRAY))
                .build();
    }

    public Logger logger() {
        return this.logger;
    }
}
