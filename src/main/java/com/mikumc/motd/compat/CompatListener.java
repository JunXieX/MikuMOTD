package com.mikumc.motd.compat;

import com.mikumc.motd.MikuMOTDPlugin;
import com.mikumc.motd.ping.PingRegistry;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import java.util.Locale;

/**
 * 事件模式兜底：快速路径注入失败或被配置禁用时，通过 {@link ProxyPingEvent}
 * 返回预构建的 {@link com.velocitypowered.api.proxy.server.ServerPing}。
 * 该模式同样不做逐次渲染，仅多一次事件分发，功能与快速路径一致。
 * 每次事件从主插件取当前注册表，重载后自动切换到新模板。
 */
public final class CompatListener {

    private final MikuMOTDPlugin plugin;

    public CompatListener(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe(priority = 200)
    public void onPing(ProxyPingEvent event) {
        PingRegistry registry = this.plugin.registryForCompat();
        if (registry == null) {
            return;
        }

        InboundConnection connection = event.getConnection();
        int protocol = connection.getProtocolVersion().getProtocol();
        String hostKey = connection.getVirtualHost()
                .map(address -> (address.getHostName() + ":" + address.getPort())
                        .toLowerCase(Locale.ROOT))
                .orElse(null);

        PingRegistry.ServerPingSelection selection = registry.selectCompat(protocol, hostKey);
        if (selection != null && selection.ping() != null) {
            event.setPing(selection.ping());
        }
    }
}
