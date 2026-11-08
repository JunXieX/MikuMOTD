package com.mikumc.motd;

import com.google.inject.Inject;
import com.mikumc.motd.command.MikuCommand;
import com.mikumc.motd.compat.CompatListener;
import com.mikumc.motd.config.MikuConfig;
import com.mikumc.motd.inject.FastPathHost;
import com.mikumc.motd.inject.Injector;
import com.mikumc.motd.ping.ResponseTemplate;
import com.mikumc.motd.util.Favicon;
import com.mikumc.motd.util.VanishSupport;
import com.velocitypowered.api.command.CommandManager;
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
import java.util.function.Consumer;
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
public final class MikuMOTDPlugin implements FastPathHost {

    private static final long FAULT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    /**
     * 数据目录固定为 plugins/MikuMOTD：{@code @DataDirectory} 按插件 id（小写 mikumotd）
     * 解析目录名，在大小写敏感的 Linux 上会另起 plugins/mikumotd，等于让既有用户丢配置。
     */
    private final Path dataDirectory = Path.of("plugins", "MikuMOTD");
    private final ProxyServer proxy;
    private final Logger logger;
    private final AtomicLong lastFaultLog = new AtomicLong();
    private final Object reloadLock = new Object();

    /** 配置与模板的一致快照；重载整体替换，旧模板由重载线程负责销毁。 */
    private record RuntimeState(MikuConfig config, ResponseTemplate template) {
    }

    private volatile RuntimeState runtime;
    /** 快速路径是否已注入（注入只需一次，之后由 fastPathEnabled 控制每条连接的行为）。 */
    private volatile boolean injected;
    /** 当前是否启用快速路径；每条新连接建立时读取。 */
    private volatile boolean fastPath;
    /** 关服后置位：阻止关服竞态中到达的 reload 重新发布快照。 */
    private boolean shuttingDown;
    /** 兜底监听器：插件启用时注册一次并常驻（快速路径消费的连接不会触达它）。 */
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
        // 命令与兜底监听器先落地：它们是配置坏掉时唯一的恢复入口与保底响应路径，
        // 不能排在注入（可能抛 LinkageError）之后
        CommandManager commands = this.proxy.getCommandManager();
        commands.register(commands.metaBuilder("mikumotd").plugin(this).build(), new MikuCommand(this));
        this.registerCompat();

        try {
            this.reload();
        } catch (Exception e) {
            // 配置非法等也走这里；保底保留事件模式与命令，让 /mikumotd reload 可用
            this.logger.error("配置加载失败，修正 plugins/MikuMOTD/config.conf 后执行 /mikumotd reload", e);
        }
        // Hook 无条件安装（即使当前是事件模式）：连接初始化器在端口绑定时就被固化进
        // ServerBootstrap，之后再装对已监听端口无效。Hook 内部按当前配置决定每条连接是否挂载，
        // 因此装一次即可让 compat-mode 的热切换双向生效
        this.installHook();
        // 配置非法（runtime 为空）时不启用快速路径：事件模式下 ping 交还原生代理，
        // 比快速路径「无帧可取 → 关连接」更合理
        RuntimeState current = this.runtime;
        this.applyMode(current != null && !current.config().compatMode());
        this.logger.info("当前响应模式：{}", this.fastPath ? "快速路径（字节级）" : "事件模式（兜底）");
    }

    private void installHook() {
        if (this.injected) {
            return;
        }
        try {
            if (Injector.inject(this, this.proxy) != null) {
                this.injected = true;
            }
        } catch (LinkageError e) {
            // Injector 的静态初始化失败会以 NoClassDefFoundError 绕过 inject 内部的 catch
            this.logger.error("快速路径不可用（代理内部结构不符），保持事件模式", e);
        }
    }

    /**
     * 切换响应模式（可重复调用；只改开关，不增删注入）。
     *
     * <p>调用前必须先执行 {@link #installHook()}：未注入成功时这里会保持事件模式。
     * 注入本身的失败已由 {@code Injector} 带堆栈记录，此处不再重复告警——
     * 否则在「先 reload、后注入」的调用顺序下会把正常流程误报成失败。</p>
     *
     * <p>快速路径的挂载点在插件启用时安装一次，之后每条新连接建立时读取
     * {@link #fastPathEnabled()}，因此 compat-mode 的热重载对之后的所有连接立即生效，
     * 两个方向都有效。兜底监听器常驻，负责覆盖快速路径不会接管的连接。</p>
     */
    private void applyMode(boolean wantFastPath) {
        boolean enable = wantFastPath && this.injected;
        if (this.fastPath == enable) {
            return;
        }
        this.fastPath = enable;
        this.logger.info(enable
                ? "已切换到快速路径（字节级状态响应）"
                : "已切换到事件模式（新连接不再走快速路径）");
    }

    private void registerCompat() {
        if (this.compatListener != null) {
            return;
        }
        CompatListener listener = new CompatListener(this);
        this.proxy.getEventManager().register(this, listener);
        this.compatListener = listener;
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        // 与 reload 互斥：避免关服瞬间的 /mikumotd reload 把新快照发布到已置空的运行时
        synchronized (this.reloadLock) {
            this.shuttingDown = true;
            // 关服后不再接受新连接，无需撤销注入（也撤不掉已固化的 childHandler）
            this.fastPath = false;
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
    }

    /**
     * 加载配置并重建模板。失败时保留旧配置继续运行并抛出异常交由调用方报告。
     */
    public boolean reload() throws IOException {
        synchronized (this.reloadLock) {
            if (this.shuttingDown) {
                throw new IOException("代理正在关服，已忽略配置重载");
            }
            MikuConfig config = new MikuConfig();
            config.load(this.dataDirectory.resolve("config.conf"));

            String descriptionJson = GSON.serialize(
                    MiniMessage.miniMessage().deserialize(config.motd().replace("{NL}", "\n")));
            String faviconUrl;
            try {
                faviconUrl = Favicon.load(config.logo(), this.dataDirectory);
            } catch (Exception e) {
                // fail-open：图标缺失/损坏只降级为无图标，不阻断配置加载
                this.reportFaviconFailure(config.logo(), e);
                faviconUrl = null;
            }
            // 人数与玩家列表取同一次快照，避免「在线数」与悬停列表条数对不上
            List<Player> online = this.visiblePlayers();
            ResponseTemplate fresh = ResponseTemplate.compile(
                    descriptionJson,
                    "MikuMOTD",
                    faviconUrl,
                    0,
                    online.size(),
                    this.maxPlayers(config),
                    sampleOf(online));

            RuntimeState previous = this.runtime;
            this.runtime = new RuntimeState(config, fresh);
            if (previous != null) {
                previous.template().dispose();
            }

            this.rescheduleRefreshTask(config);
            // 保留原配置 → 新建状态 → 切换模式。注入必须先于模式切换：
            // applyMode 依据 injected 决定能否启用快速路径，顺序颠倒会把首次启动误判为注入失败
            this.installHook();
            this.applyMode(!config.compatMode());
            this.logger.info("配置已加载（logo：{}，人数上限：{}）",
                    config.logo(), this.maxPlayers(config));
            return true;
        }
    }

    /**
     * 异步重载：配置解析与图标解码会触发文件 IO 与 AWT 初始化，不能占用命令线程
     * （玩家命令在连接的事件循环上执行）。结果在调度线程回调；{@code sendMessage}
     * 最终走 {@code channel.writeAndFlush}，跨线程写是安全的。
     */
    public void requestReload(Consumer<Boolean> onResult) {
        this.proxy.getScheduler().buildTask(this, () -> {
            boolean ok;
            try {
                ok = this.reload();
            } catch (Exception e) {
                this.logger.error("配置重载失败", e);
                ok = false;
            }
            onResult.accept(ok);
        }).schedule();
    }

    /** 图标加载失败只降级为无图标：预期失败（文件缺失/格式非法）记一行，非预期保留堆栈。 */
    private void reportFaviconFailure(String logo, Exception cause) {
        if (cause instanceof IOException) {
            this.logger.warn("图标加载失败（本次将不含图标）：{}（{}）", logo, cause.toString());
        } else {
            this.logger.warn("图标加载失败（本次将不含图标）：{}", logo, cause);
        }
    }

    private void rescheduleRefreshTask(MikuConfig config) {
        ScheduledTask previous = this.refreshTask;
        // 先建新任务再取消旧任务：建任务失败时旧任务仍在跑，不会出现「新配置已生效但人数停更」
        this.refreshTask = this.proxy.getScheduler()
                .buildTask(this, this::refresh)
                .delay(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .repeat(config.updateIntervalMs(), TimeUnit.MILLISECONDS)
                .schedule();
        if (previous != null) {
            previous.cancel();
        }
    }

    private void refresh() {
        RuntimeState current = this.runtime;
        if (current == null) {
            return;
        }
        List<Player> online = this.visiblePlayers();
        current.template().update(online.size(), this.maxPlayers(current.config()), sampleOf(online));
    }

    /** 人数上限：插件覆盖值优先，0 沿用代理原生 show-max-players（负值按 0 处理）。 */
    private int maxPlayers(MikuConfig config) {
        int override = config.maxPlayers();
        return override > 0 ? override : Math.max(0, this.proxy.getConfiguration().getShowMaxPlayers());
    }

    /** 可见在线玩家：剔除隐身玩家，按 UUID 排序（与原生代理同为 12 行上限）。人数与列表共用这一份。 */
    private List<Player> visiblePlayers() {
        Set<UUID> vanished = VanishSupport.vanishedPlayers();
        List<Player> online = new ArrayList<>(this.proxy.getAllPlayers());
        if (!vanished.isEmpty()) {
            online.removeIf(player -> vanished.contains(player.getUniqueId()));
        }
        online.sort(Comparator.comparing(Player::getUniqueId));
        return online;
    }

    private static List<ResponseTemplate.SampleEntry> sampleOf(List<Player> online) {
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

    @Override
    public boolean fastPathEnabled() {
        return this.fastPath;
    }

    /**
     * 每次状态请求选择响应帧；插件未就绪或重载竞态时返回 null（连接将被关闭）。
     *
     * <p>重载会「先发布新快照、再销毁旧模板」。若 ping 恰好在销毁瞬间仍持有旧
     * {@link RuntimeState}，取帧会失败；此时重读一次当前状态即可命中新模板，
     * 避免把极窄的竞态窗口暴露成一次连接被断。</p>
     */
    @Override
    public ByteBuf selectResponse(int protocol) {
        for (int attempt = 0; attempt < 2; attempt++) {
            RuntimeState current = this.runtime;
            if (current == null) {
                return null;
            }
            ByteBuf frame = current.template().acquire(protocol);
            if (frame != null) {
                return frame;
            }
            if (this.runtime == current) {
                // 仍是同一代却取不到帧：模板确实未就绪，交由调用方关闭连接
                return null;
            }
        }
        return null;
    }

    /** 事件模式兜底取当前预构建 ping；未就绪时返回 null（交还原生代理行为）。 */
    public ServerPing statusPing() {
        RuntimeState current = this.runtime;
        if (current == null) {
            return null;
        }
        ServerPing ping = current.template().acquireCompat();
        if (ping != null || this.runtime == current) {
            return ping;
        }
        // 该代模板刚被重载销毁：重读一次新状态，避免这一次 ping 回落成代理原生 MOTD
        RuntimeState fresh = this.runtime;
        return fresh == null ? null : fresh.template().acquireCompat();
    }

    @Override
    public boolean allowImproperPings() {
        RuntimeState current = this.runtime;
        return current != null && current.config().allowImproperPings();
    }

    @Override
    public void onStatusHandshake(java.net.SocketAddress remote, int protocol) {
        RuntimeState current = this.runtime;
        if (current != null && current.config().logPings()) {
            this.logger.info("{} 正在以协议 {} 进行状态查询", remote, protocol);
        }
    }

    @Override
    public void logImproperPing(java.net.SocketAddress remote, String detail) {
        RuntimeState current = this.runtime;
        if (current != null && current.config().logImproperPings()) {
            this.logger.warn("{} 非标准 ping 序列：{}", remote, detail);
        }
    }

    /** 快速路径内部异常的限速日志（避免被畸形包刷屏）。 */
    @Override
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
                .append(Component.text(" ｜ 在线：" + this.visiblePlayers().size(), NamedTextColor.AQUA))
                .append(Component.text(" ｜ 刷新间隔：" + current.config().updateIntervalMs() + "ms",
                        NamedTextColor.GRAY))
                .build();
    }

    public Logger logger() {
        return this.logger;
    }
}
