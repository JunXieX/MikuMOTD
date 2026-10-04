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
 * <p>反射句柄在类加载时一次性解析并缓存；未安装时句柄为 null，调用恒走降级分支，
 * 不触发任何 API 类解析（所有对 API 的引用都隔离在内部 Hook 类中，
 * 仅在探测成功后才会被加载）。刷新路径为秒级低频，反射开销可忽略。</p>
 */
public final class VanishSupport {

    private static final boolean PRESENT = detect();
    private static final MethodHandle IS_AVAILABLE;
    private static final MethodHandle IS_VANISHED;
    private static final MethodHandle GET_VANISHED_PLAYERS;

    static {
        MethodHandle isAvailable = null;
        MethodHandle isVanished = null;
        MethodHandle getVanishedPlayers = null;
        if (PRESENT) {
            try {
                Class<?> api = Class.forName("dev.junxiex.mikuvanish.api.MikuVanishAPI");
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                isAvailable = lookup.findStatic(api, "isAvailable",
                        MethodType.methodType(boolean.class));
                isVanished = lookup.findStatic(api, "isVanished",
                        MethodType.methodType(boolean.class, UUID.class));
                getVanishedPlayers = lookup.findStatic(api, "getVanishedPlayers",
                        MethodType.methodType(Set.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // API 类存在但结构不符：视为未安装
                isAvailable = null;
                isVanished = null;
                getVanishedPlayers = null;
            }
        }
        IS_AVAILABLE = isAvailable;
        IS_VANISHED = isVanished;
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

    /** MikuVanish 是否已安装且其服务已注册。 */
    public static boolean available() {
        if (!PRESENT || IS_AVAILABLE == null) {
            return false;
        }
        try {
            return (boolean) IS_AVAILABLE.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家当前是否处于隐身状态（未安装时恒为 false）。 */
    public static boolean isVanished(UUID uuid) {
        if (!PRESENT || IS_VANISHED == null) {
            return false;
        }
        try {
            return (boolean) IS_VANISHED.invokeExact(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前隐身玩家集合（未安装时返回空集合）。 */
    public static Set<UUID> vanishedPlayers() {
        if (!PRESENT || GET_VANISHED_PLAYERS == null) {
            return Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Set<UUID> vanished = (Set<UUID>) GET_VANISHED_PLAYERS.invokeExact();
            return vanished;
        } catch (Throwable t) {
            return Set.of();
        }
    }
}
