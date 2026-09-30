package com.mikumc.motd;

import com.google.inject.Inject;
import com.mikumc.motd.command.MikuCommand;
import com.mikumc.motd.compat.CompatListener;
import com.mikumc.motd.config.MikuConfig;
import com.mikumc.motd.config.TextFormat;
import com.mikumc.motd.guard.MaintenanceGuard;
import com.mikumc.motd.inject.Injector;
import com.mikumc.motd.ping.PingRegistry;
import com.mikumc.motd.ping.ResponseTemplate;
import com.mikumc.motd.ping.TemplateFactory;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

/**
 * MikuMOTD：MikuMC 服务器的 Velocity 代理 MOTD 插件。
 *
 * <p>核心路径（状态查询）完全在 Netty 字节层完成：响应帧预渲染、零拷贝分发，
 * 不进入代理会话处理；配置、维护模式、兼容兜底与命令走公开 API。</p>
 */
@Plugin(
        id = "mikumotd",
        name = "MikuMOTD",
        version = "1.0.0",
        description = "MikuMC 服务器原创插件，作者 JunXieX，交流群 1105054380",
        authors = {"JunXieX"}
)
public final class MikuMOTDPlugin {

    private static final long FAULT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicLong lastFaultLog = new AtomicLong();

    private final Object reloadLock = new Object();

    private volatile MikuConfig config;
    private volatile PingRegistry registry;
    private volatile boolean fastPath;
    private volatile boolean maintenanceOverride;
    private volatile ScheduledTask updateTask;

    @Inject
    public MikuMOTDPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            this.reload();
        } catch (IOException e) {
            this.logger.error("配置加载失败，插件未启用", e);
            return;
        }

        MikuConfig config = this.config;
        if (!config.compatMode()) {
            if (Injector.inject(this, this.proxy) != null) {
                this.fastPath = true;
                this.logger.info("快速路径已启用（字节级状态响应）");
            }
        } else {
            this.logger.info("已按配置强制使用事件模式");
        }
        if (!this.fastPath) {
            this.proxy.getEventManager().register(this, new CompatListener(this));
            this.logger.info("事件模式兜底已注册");
        }

        this.proxy.getEventManager().register(this, new MaintenanceGuard(this));

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
        ScheduledTask task = this.updateTask;
        if (task != null) {
            task.cancel();
        }
        PingRegistry current = this.registry;
        if (current != null) {
            current.dispose();
        }
    }

    /**
     * 加载配置并重建全部模板。失败时保留旧配置继续运行并抛出异常交由调用方报告。
     */
    public boolean reload() throws IOException {
        synchronized (this.reloadLock) {
            MikuConfig config = new MikuConfig();
            Path configFile = this.dataDirectory.resolve("config.conf");
            config.load(configFile);

            TemplateFactory factory = new TemplateFactory(
                    this.dataDirectory, config.textFormat(), config.pngQuality(), this.logger);
            PingRegistry fresh = PingRegistry.build(config, factory);
            // 维护状态跟随配置初始化；游戏内切换只改运行时标志，重启后回到配置值
            boolean maintenance = config.maintenanceEnabled();
            this.maintenanceOverride = maintenance;
            fresh.setMaintenance(maintenance);

            this.config = config;
            PingRegistry previous = this.registry;
            this.registry = fresh;
            if (previous != null) {
                previous.dispose();
            }

            this.rescheduleUpdateTask(config);
            this.logger.info("配置已加载（维护模式：{}，文本格式：{}）",
                    maintenance ? "开" : "关", config.textFormat());
            return true;
        }
    }

    private void rescheduleUpdateTask(MikuConfig config) {
        ScheduledTask previous = this.updateTask;
        if (previous != null) {
            previous.cancel();
        }
        this.updateTask = this.proxy.getScheduler()
                .buildTask(this, this::refreshCounts)
                .delay(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .repeat(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void refreshCounts() {
        MikuConfig config = this.config;
        PingRegistry registry = this.registry;
        if (config == null || registry == null) {
            return;
        }

        boolean maintenance = this.maintenanceOverride;
        long counted = (long) (this.proxy.getPlayerCount() + config.fakeOnlineFixed())
                * (100L + config.fakeOnlinePercent()) / 100L;
        int online = (int) Math.min(counted, 99_999_999L);
        int max = config.maxCountType() == MikuConfig.MaxCountType.ADD
                ? online + config.maxCount()
                : config.maxCount();
        max = Math.max(0, Math.min(max, 99_999_999));
        if (maintenance) {
            if (config.maintenanceOverrideOnline() >= 0) {
                online = config.maintenanceOverrideOnline();
            }
            if (config.maintenanceOverrideMaxOnline() >= 0) {
                max = config.maintenanceOverrideMaxOnline();
            }
        }
        registry.update(maintenance, online, max);
    }

    // ---------------------------------------------------------------------
    // 供快速路径（事件循环高频）调用的只读入口
    // ---------------------------------------------------------------------

    /** 每次状态请求选择响应模板；插件未就绪或重载竞态时返回 null（连接将被关闭）。 */
    public ResponseTemplate selectResponse(int protocol, String hostKey) {
        PingRegistry registry = this.registry;
        return registry == null ? null : registry.select(protocol, hostKey);
    }

    /** 事件模式兜底取当前注册表。 */
    public PingRegistry registryForCompat() {
        return this.registry;
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

    // ---------------------------------------------------------------------
    // 维护模式
    // ---------------------------------------------------------------------

    public void setMaintenance(boolean enabled) {
        this.maintenanceOverride = enabled;
        PingRegistry registry = this.registry;
        if (registry != null) {
            registry.setMaintenance(enabled);
        }
    }

    public boolean isMaintenance() {
        return this.maintenanceOverride;
    }

    public boolean isMaintenanceKickOnJoin() {
        MikuConfig config = this.config;
        return config != null && this.maintenanceOverride && config.maintenanceKickOnJoin();
    }

    public boolean isKickWhitelisted(InetAddress address) {
        MikuConfig config = this.config;
        if (config == null) {
            return false;
        }
        List<InetAddress> whitelist = config.kickWhitelist();
        for (InetAddress allowed : whitelist) {
            if (allowed.equals(address)) {
                return true;
            }
        }
        return false;
    }

    public Component kickMessage() {
        MikuConfig config = this.config;
        String raw = config != null ? config.kickMessage() : "服务器维护中";
        return TextFormat.MINIMESSAGE.deserialize(raw.replace("{NL}", "\n"));
    }

    // ---------------------------------------------------------------------
    // 状态展示
    // ---------------------------------------------------------------------

    public Component statusInfo() {
        MikuConfig config = this.config;
        PingRegistry registry = this.registry;
        if (config == null || registry == null) {
            return Component.text("MikuMOTD 尚未就绪", NamedTextColor.RED);
        }
        String mode = this.fastPath ? "快速路径（字节级）" : "事件模式（兜底）";
        return Component.text()
                .append(Component.text("MikuMOTD", TextColor.color(0x40c4ff)))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("模式：" + mode, NamedTextColor.GREEN))
                .append(Component.text(" ｜ 维护：" + (this.maintenanceOverride ? "开" : "关"),
                        this.maintenanceOverride ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(Component.text(" ｜ 在线：" + this.proxy.getPlayerCount(), NamedTextColor.AQUA))
                .append(Component.text(" ｜ 刷新间隔：" + config.updateIntervalMs() + "ms",
                        NamedTextColor.GRAY))
                .build();
    }

    public Logger logger() {
        return this.logger;
    }
}
