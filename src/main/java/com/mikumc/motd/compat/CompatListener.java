package com.mikumc.motd.compat;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;

/**
 * 事件模式兜底：快速路径注入失败或被配置禁用时，通过 {@link ProxyPingEvent}
 * 返回预构建的 {@link ServerPing}（按客户端协议号替换版本信息）。
 * 每次事件从主插件取当前预构建 ping，重载后自动切换。
 */
public final class CompatListener {

    private final MikuMOTDPlugin plugin;

    public CompatListener(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * {@code priority} 越大越早执行（Velocity 按降序排列监听器），
     * 因此取 {@link Short#MIN_VALUE} 让本监听器最后运行：
     * 事件模式下本插件的 MOTD 才能最终生效，而不是被其它 MOTD 插件覆盖。
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onPing(ProxyPingEvent event) {
        ServerPing base = this.plugin.statusPing();
        if (base == null) {
            // 插件未就绪：不修改事件结果，交还原生代理的 ping
            return;
        }

        int protocol = event.getConnection().getProtocolVersion().getProtocol();
        ServerPing.Version version = base.getVersion();
        event.setPing(version.getProtocol() == protocol ? base
                : base.asBuilder().version(new ServerPing.Version(protocol, version.getName())).build());
    }
}
