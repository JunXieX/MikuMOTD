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

    private static final MethodHandle GET_VANISHED_PLAYERS = resolve();

    private VanishSupport() {
    }

    /**
     * 一次性探测即可：代理在触发 ProxyInitializeEvent 之前已完成全部插件装载，
     * 且插件类加载器找不到类时会互相查找，因此「已安装但此刻类不可见」不会发生。
     * 真正会自愈的窗口是「类已加载但服务尚未注册」——那次调用抛 IllegalStateException，
     * 由 {@link #vanishedPlayers()} 兜住，注册后自动恢复。
     */
    private static MethodHandle resolve() {
        try {
            Class<?> api = Class.forName("dev.junxiex.mikuvanish.api.MikuVanishAPI");
            return MethodHandles.lookup().findStatic(api, "getVanishedPlayers",
                    MethodType.methodType(Set.class));
        } catch (Exception | LinkageError e) {
            // 未安装（ClassNotFoundException）、API 结构不符或链接失败：一律视为未安装
            return null;
        }
    }

    /** 当前隐身玩家集合（未安装或服务未就绪时返回空集合）。 */
    public static Set<UUID> vanishedPlayers() {
        MethodHandle handle = GET_VANISHED_PLAYERS;
        if (handle == null) {
            return Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Set<UUID> vanished = (Set<UUID>) handle.invokeExact();
            return vanished;
        } catch (Throwable t) {
            // invokeExact 声明抛出 Throwable，只能整体兜住：服务未注册（启动/关服窗口）
            // 与它的类初始化失败都会走到这里，下一次查询自动重试
            return Set.of();
        }
    }
}
