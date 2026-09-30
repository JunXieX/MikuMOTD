package com.mikumc.motd.guard;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 维护模式的登录防线：开启「登录踢出」时，白名单以外的玩家在预登录阶段即被拒绝。
 * 白名单以预解析的 IP 比较（配置加载时完成，登录路径零 DNS 查询）。
 */
public final class MaintenanceGuard {

    private final MikuMOTDPlugin plugin;

    public MaintenanceGuard(MikuMOTDPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe(priority = 200)
    public void onPreLogin(PreLoginEvent event) {
        if (!this.plugin.isMaintenanceKickOnJoin()) {
            return;
        }

        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        InetAddress address = remote != null ? remote.getAddress() : null;
        if (address != null && this.plugin.isKickWhitelisted(address)) {
            return;
        }

        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(this.plugin.kickMessage()));
    }
}
