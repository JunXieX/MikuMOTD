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
import com.velocitypowered.api.proxy.server.ServerPing;
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
 *
 * <p>配置与模板成对发布：{@link RuntimeState} 一次性替换，读侧（事件循环 / 命令 / 兜底监听器）
 * 永远看到同一份配置与同一条模板，不存在「新配置 + 旧模板」或「旧模板 + 新偏移」的组合。</p>
 */
@Plugin(
        id = "mikumotd",
        name = "MikuMOTD",
        version = BuildInfo.VERSION,
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

    /** 配置与模板的一致快照；重载整体替换，旧模板由重载线程负责销毁。 */
    private record RuntimeState(MikuConfig config, ResponseTemplate template) {
    }

    private volatile RuntimeState runtime;
    private volatile boolean fastPath;
    /** 兜底监听器实例：仅在事件模式下注册，重载切模式时需要精确注销。 */
    private volatile CompatListener compatListener;
    /** 调度线程、配置线程与关服线程共享，故必须是 volatile。 */
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
            this.logger.error("配置加载失败，修正 plugins/MikuMOTD/config.conf 后执行 /mikumotd reload", e);
        }
        // 配置非法（runtime 为空）时不启用快速路径：事件模式下 ping 交还原生代理，
        // 比快速路径「无帧可取 → 关连接」更合理
        RuntimeState current = this.runtime;
        this.applyMode(current != null && !current.config().compatMode());

        CommandManager commands = this.proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("mikumotd")
                .plugin(this)
                .build();
        commands.register(meta, new MikuCommand(this));
    }

    /**
     * 在「快速路径」与「事件模式」之间按需切换（可重复调用，已处于目标状态时为空操作）。
     *
     * <p>任何时刻都保证有且只有一条响应路径：转事件模式先注册再拆注入；转快速路径先注入成功
     * 再注销兜底。两方向都把「先建立新路径、后拆除旧路径」作为顺序，因此不存在响应真空；
     * 注入失败时保持事件模式，功能始终完整。</p>
     */
    private void applyMode(boolean wantFastPath) {
        if (!wantFastPath) {
            if (this.compatListener != null) {
                return;
            }
            this.registerCompat();
            if (this.fastPath) {
                Injector.restore(this, this.proxy);
                this.fastPath = false;
            }
            this.logger.info("已启用事件模式兜底");
            return;
        }

        if (this.fastPath) {
            return;
        }
        if (Injector.inject(this, this.proxy) == null) {
            // 注入失败：确保仍有响应路径（首次启动或从事件模式切换时）
            if (this.compatListener == null) {
                this.registerCompat();
                this.logger.warn("快速路径注入失败，已回退到事件模式（功能不受影响，仅吞吐较低）");
            }
            return;
        }
        // 快速路径已在所有新连接上生效，事件模式下才注册的兜底监听器此时冗余
        CompatListener listener = this.compatListener;
        if (listener != null) {
            this.proxy.getEventManager().unregisterListener(this, listener);
            this.compatListener = null;
        }
        this.fastPath = true;
        this.logger.info("已启用快速路径（字节级状态响应）");
    }

    private void registerCompat() {
        CompatListener listener = new CompatListener(this);
        this.proxy.getEventManager().register(this, listener);
        this.compatListener = listener;
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.fastPath) {
            Injector.restore(this, this.proxy);
        }
        ScheduledTask task = this.refreshTask;
        if (task != null) {
            // 已取消的任务可能仍在执行，runtime 置空后其 update 会被模板自行忽略
            task.cancel();
        }
        RuntimeState current = this.runtime;
        this.runtime = null;
        if (current != null) {
            current.template().dispose();
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
                faviconUrl = Favicon.load(config.logo(), this.dataDirectory);
            } catch (Exception e) {
                // fail-open：图标缺失/损坏只降级为无图标，不阻断配置加载
                this.logger.warn("图标加载失败（本次将不含图标）：{}", config.logo(), e);
                faviconUrl = null;
            }
            Set<UUID> vanished = VanishSupport.vanishedPlayers();
            ResponseTemplate fresh = ResponseTemplate.compile(
                    descriptionJson,
                    "MikuMOTD",
                    faviconUrl,
                    0,
                    this.visiblePlayerCount(vanished),
                    this.maxPlayers(config),
                    this.currentSample(vanished));

            RuntimeState previous = this.runtime;
            this.runtime = new RuntimeState(config, fresh);
            if (previous != null) {
                previous.template().dispose();
            }

            this.rescheduleRefreshTask(config);
            // 让 compat-mode 的改动即时生效（未变化时为空操作）
            this.applyMode(!config.compatMode());
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
        RuntimeState current = this.runtime;
        if (current == null) {
            return;
        }
        // 隐身集合每次刷新只查一次，人数与玩家列表共用同一快照
        Set<UUID> vanished = VanishSupport.vanishedPlayers();
        current.template().update(
                this.visiblePlayerCount(vanished),
                this.maxPlayers(current.config()),
                this.currentSample(vanished));
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
    private int visiblePlayerCount(Set<UUID> vanished) {
        if (vanished.isEmpty()) {
            return this.proxy.getPlayerCount();
        }
        return (int) this.proxy.getAllPlayers().stream()
                .filter(player -> !vanished.contains(player.getUniqueId()))
                .count();
    }

    /** 真实在线玩家快照：剔除隐身玩家，按 UUID 排序（与原生代理同为 12 行上限）。 */
    private List<ResponseTemplate.SampleEntry> currentSample(Set<UUID> vanished) {
        List<Player> online = new ArrayList<>(this.proxy.getAllPlayers());
        online.removeIf(player -> vanished.contains(player.getUniqueId()));
        online.sort(Comparator.comparing(Player::getUniqueId));
        int rows = Math.min(online.size(), ResponseTemplate.MAX_SAMPLE_ROWS);
        List<ResponseTemplate.SampleEntry> entries = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            Player player = online.get(i);
            entries.add(new ResponseTemplate.SampleEntry(player.getUniqueId(), player.getUsername()));
        }
        return entries;
    }

    // ---------------------------------------------------------------------
    // 供快速路径（事件循环高频）调用的只读入口
    // ---------------------------------------------------------------------

    /** 每次状态请求选择响应帧；插件未就绪或重载竞态时返回 null（连接将被关闭）。 */
    public ByteBuf selectResponse(int protocol) {
        RuntimeState current = this.runtime;
        return current == null ? null : current.template().acquire(protocol);
    }

    /** 事件模式兜底取当前预构建 ping；未就绪时返回 null（交还原生代理行为）。 */
    public ServerPing statusPing() {
        RuntimeState current = this.runtime;
        return current == null ? null : current.template().acquireCompat();
    }

    public boolean directWrite() {
        RuntimeState current = this.runtime;
        return current != null && current.config().directWrite();
    }

    public boolean allowImproperPings() {
        RuntimeState current = this.runtime;
        return current != null && current.config().allowImproperPings();
    }

    public void onStatusHandshake(java.net.SocketAddress remote, int protocol) {
        RuntimeState current = this.runtime;
        if (current != null && current.config().logPings()) {
            this.logger.info("{} 正在以协议 {} 进行状态查询", remote, protocol);
        }
    }

    public void logImproperPing(java.net.SocketAddress remote, String detail) {
        RuntimeState current = this.runtime;
        if (current != null && current.config().logImproperPings()) {
            this.logger.warn("{} 非标准 ping 序列：{}", remote, detail);
        }
    }

    /** 快速路径内部异常的限速日志（避免被畸形包刷屏）。 */
    public void logFastPathFault(java.net.SocketAddress remote, Throwable cause) {
        long now = System.nanoTime();
        long last = this.lastFaultLog.get();
        if (now - last >= FAULT_LOG_INTERVAL_NANOS && this.lastFaultLog.compareAndSet(last, now)) {
            this.logger.warn("快速路径处理 {} 出现异常，连接已关闭（限速日志）", remote, cause);
        }
    }

    public Component statusInfo() {
        RuntimeState current = this.runtime;
        if (current == null) {
            return Component.text("MikuMOTD 尚未就绪", NamedTextColor.RED);
        }
        String mode = this.fastPath ? "快速路径（字节级）" : "事件模式（兜底）";
        return Component.text()
                .append(Component.text("MikuMOTD", TextColor.color(0x40c4ff)))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("模式：" + mode, NamedTextColor.GREEN))
                .append(Component.text(" ｜ 人数上限：" + this.maxPlayers(current.config()), NamedTextColor.AQUA))
                .append(Component.text(" ｜ 在线：" + this.visiblePlayerCount(VanishSupport.vanishedPlayers()),
                        NamedTextColor.AQUA))
                .append(Component.text(" ｜ 刷新间隔：" + current.config().updateIntervalMs() + "ms",
                        NamedTextColor.GRAY))
                .build();
    }

    public Logger logger() {
        return this.logger;
    }
}
