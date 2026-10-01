package com.mikumc.motd.ping;

import com.mikumc.motd.config.MikuConfig;
import com.mikumc.motd.config.ProfileData;
import com.velocitypowered.api.proxy.server.ServerPing;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 全部响应模板的注册表：默认画像、按协议版本段画像与按域名画像（普通/维护两套）。
 * 内容完全一致的画像共享同一实例以节省内存；整表为不可变快照，重载时整体替换。
 */
public final class PingRegistry {

    private static final int MAX_RANGE_SPAN = 4096;

    private record Snapshot(
            PingProfile defaultProfile,
            Int2ObjectOpenHashMap<PingProfile> protocols,
            Map<String, PingProfile> domains
    ) {
    }

    private record State(Snapshot normal, Snapshot maintenance, List<PingProfile> allProfiles) {
    }

    private final State state;
    private volatile boolean maintenance;

    private PingRegistry(State state) {
        this.state = state;
    }

    /** 由配置构建注册表；所有模板渲染在本方法（配置线程）内完成。 */
    public static PingRegistry build(MikuConfig config, TemplateFactory factory, org.slf4j.Logger logger) {
        Map<String, PingProfile> cache = new HashMap<>();
        List<PingProfile> all = new ArrayList<>();

        // 维护模式隐藏真实协议（show-real-version=false）时，维护集使用固定协议号的模板
        Snapshot normal = buildSnapshot(config.defaultProfile(), config.protocolProfiles(),
                config.domainProfiles(), false, factory, cache, all, logger);
        Snapshot maintenance = buildSnapshot(config.maintenanceProfile(), config.maintenanceProtocolProfiles(),
                config.maintenanceDomainProfiles(), !config.maintenanceShowRealVersion(),
                factory, cache, all, logger);
        return new PingRegistry(new State(normal, maintenance, List.copyOf(all)));
    }

    private static Snapshot buildSnapshot(ProfileData defaultData,
                                          Map<String, ProfileData> protocolData,
                                          Map<String, ProfileData> domainData,
                                          boolean fixedProtocol,
                                          TemplateFactory factory,
                                          Map<String, PingProfile> cache,
                                          List<PingProfile> all,
                                          org.slf4j.Logger logger) {
        PingProfile defaultProfile = internProfile(defaultData, fixedProtocol, factory, cache, all);

        Int2ObjectOpenHashMap<PingProfile> protocols = new Int2ObjectOpenHashMap<>();
        protocolData.forEach((range, data) -> {
            PingProfile profile = internProfile(data, fixedProtocol, factory, cache, all);
            for (int protocol : expandRange(range, logger)) {
                protocols.put(protocol, profile);
            }
        });

        Map<String, PingProfile> domains = new LinkedHashMap<>();
        domainData.forEach((host, data) ->
                domains.put(host.toLowerCase(Locale.ROOT),
                        internProfile(data, fixedProtocol, factory, cache, all)));

        return new Snapshot(defaultProfile, protocols, domains);
    }

    /** 相同内容的画像共享一个实例（固定协议与真实协议是不同变体，不共享）。 */
    private static PingProfile internProfile(ProfileData data, boolean fixedProtocol, TemplateFactory factory,
                                             Map<String, PingProfile> cache, List<PingProfile> all) {
        String key = data.versionName() + '\u0000'
                + String.join("\u0001", data.descriptions()) + '\u0000'
                + String.join("\u0001", data.favicons()) + '\u0000'
                + String.join("\u0001", data.playerList()) + '\u0000'
                + fixedProtocol + '\u0000'
                + data.realPlayers();
        return cache.computeIfAbsent(key, ignored -> {
            PingProfile profile = new PingProfile(factory.compile(data, fixedProtocol));
            all.add(profile);
            return profile;
        });
    }

    private static int[] expandRange(String spec, org.slf4j.Logger logger) {
        String trimmed = spec.trim();
        int dash = trimmed.indexOf('-');
        int from;
        int to;
        try {
            if (dash >= 0) {
                from = Integer.parseInt(trimmed.substring(0, dash).trim());
                to = Integer.parseInt(trimmed.substring(dash + 1).trim());
            } else {
                from = to = Integer.parseInt(trimmed);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("协议段格式错误: " + spec);
        }
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("协议段范围无效: " + spec);
        }
        if (to - from + 1 > MAX_RANGE_SPAN) {
            logger.warn("协议段 {} 覆盖 {} 个版本，超出上限 {}，已截断到 {}",
                    spec, to - from + 1, MAX_RANGE_SPAN, from + MAX_RANGE_SPAN - 1);
            to = from + MAX_RANGE_SPAN - 1;
        }
        int[] result = new int[to - from + 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = from + i;
        }
        return result;
    }

    /**
     * 快速路径选择（每次 ping 调用，无锁）：域名画像优先，其次协议段画像，最后默认画像。
     */
    public ResponseTemplate select(int protocol, String hostKey) {
        Snapshot snapshot = this.maintenance ? this.state.maintenance() : this.state.normal();
        PingProfile profile = null;
        if (hostKey != null) {
            profile = snapshot.domains().get(hostKey);
        }
        if (profile == null) {
            profile = snapshot.protocols().get(protocol);
        }
        if (profile == null) {
            profile = snapshot.defaultProfile();
        }
        return profile.next();
    }

    /** 事件模式兜底的同序选择。 */
    public ServerPingSelection selectCompat(int protocol, String hostKey) {
        ResponseTemplate template = select(protocol, hostKey);
        return new ServerPingSelection(template, template != null ? template.acquireCompat(protocol) : null);
    }

    public void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public boolean isMaintenance() {
        return this.maintenance;
    }

    /**
     * 人数刷新（调度线程）：按当前模式选对应画像集刷新。
     * 数值已由调用方完成换算与覆盖（普通集按人数规则，维护集按 override 规则）。
     * 共享画像实例可能被刷新多次（同值幂等，开销可忽略）。
     */
    public void update(boolean maintenance, int online, int max) {
        Snapshot snapshot = maintenance ? this.state.maintenance() : this.state.normal();
        snapshot.defaultProfile().update(online, max);
        for (PingProfile profile : snapshot.protocols().values()) {
            profile.update(online, max);
        }
        for (PingProfile profile : snapshot.domains().values()) {
            profile.update(online, max);
        }
    }

    public void dispose() {
        for (PingProfile profile : this.state.allProfiles()) {
            profile.dispose();
        }
    }

    /** 事件模式的选中结果。 */
    public record ServerPingSelection(ResponseTemplate template, ServerPing ping) {
    }
}
