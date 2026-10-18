package com.mikumc.motd.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.UUID;

/**
 * MikuVanish 的软依赖桥：运行时按需加载 dev.junxiex.mikuvanish.api.MikuVanishAPI，
 * 未安装 MikuVanish 时全部查询返回「无人隐身」。
 *
 * <p>方法句柄在类加载时一次性解析并缓存；未安装时句柄为 null，调用恒走降级分支。
 * 此处只用 {@code Class.forName} + {@code MethodHandle}，不在类常量池里写死 API 类型，
 * 因此未安装 MikuVanish 时不会因缺少类而链接失败。刷新路径为秒级低频，反射开销可忽略。</p>
 */
public final class VanishSupport {

    private static final boolean PRESENT = detect();
    private static final MethodHandle GET_VANISHED_PLAYERS;

    static {
        MethodHandle getVanishedPlayers = null;
        if (PRESENT) {
            try {
                Class<?> api = Class.forName("dev.junxiex.mikuvanish.api.MikuVanishAPI");
                getVanishedPlayers = MethodHandles.lookup().findStatic(api, "getVanishedPlayers",
                        MethodType.methodType(Set.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // API 类存在但结构不符：视为未安装
                getVanishedPlayers = null;
            }
        }
        GET_VANISHED_PLAYERS = getVanishedPlayers;
    }

    private VanishSupport() {
    }

    private static boolean detect() {
        try {
            Class.forName("dev.junxiex.mikuvanish.api.MikuVanishAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前隐身玩家集合（未安装或服务未就绪时返回空集合）。 */
    public static Set<UUID> vanishedPlayers() {
        if (!PRESENT || GET_VANISHED_PLAYERS == null) {
            return Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Set<UUID> vanished = (Set<UUID>) GET_VANISHED_PLAYERS.invokeExact();
            return vanished;
        } catch (Throwable t) {
            // MikuVanish 已加载但服务尚未注册（启动/关服窗口）
            return Set.of();
        }
    }
}
