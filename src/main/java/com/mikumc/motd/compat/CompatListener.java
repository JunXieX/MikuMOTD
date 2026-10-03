package com.mikumc.motd.compat;

import com.mikumc.motd.MikuMOTDPlugin;
import com.mikumc.motd.ping.ResponseTemplate;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;

/**
 * 事件模式兜底：快速路径注入失败或被配置禁用时，通过 {@link ProxyPingEvent}
 * 返回预构建的 {@link ServerPing}（按客户端协议号替换版本信息）。
 * 每次事件从主插件取当前模板，重载后自动切换。
 */
public final class CompatListener {

    private final MikuMOTDPlugin plugin;

    public CompatListener(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe(priority = 200)
    public void onPing(ProxyPingEvent event) {
        ResponseTemplate template = this.plugin.templateForCompat();
        if (template == null) {
            return;
        }

        ServerPing base = template.acquireCompat();
        if (base == null) {
            return;
        }

        int protocol = event.getConnection().getProtocolVersion().getProtocol();
        ServerPing.Version version = base.getVersion();
        event.setPing(version.getProtocol() == protocol ? base
                : base.asBuilder().version(new ServerPing.Version(protocol, version.getName())).build());
    }
}
