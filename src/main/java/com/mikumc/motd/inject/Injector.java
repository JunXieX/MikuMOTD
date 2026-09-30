package com.mikumc.motd.inject;

import com.mikumc.motd.MikuMOTDPlugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.network.ConnectionManager;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.network.ServerChannelInitializerHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * 通道初始化器的挂载：包装 Velocity 服务器端的原有初始化器，
 * 在每条新连接的帧解码器之后插入 {@link StatusFastPath}。
 *
 * <p>Velocity 在监听端口绑定之前触发插件初始化事件，因此替换
 * {@link ServerChannelInitializerHolder} 中的初始化器即可覆盖所有后续连接。
 * 访问链中唯一的私有成员（ConnectionManager#cm 与 holder 的初始化器字段）
 * 通过一次性反射获取；字段反射失败时回退到公开的 set() 方法。</p>
 */
public final class Injector {

    private static final MethodHandle INIT_CHANNEL;

    static {
        try {
            INIT_CHANNEL = MethodHandles.privateLookupIn(ChannelInitializer.class, MethodHandles.lookup())
                    .findVirtual(ChannelInitializer.class, "initChannel",
                            MethodType.methodType(void.class, Channel.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Injector() {
    }

    /**
     * 执行注入。
     *
     * @return 成功返回包装前的原初始化器；失败返回 null（调用方应转入事件模式兜底）
     */
    public static ChannelInitializer<Channel> inject(MikuMOTDPlugin plugin, ProxyServer proxyServer) {
        try {
            VelocityServer server = (VelocityServer) proxyServer;
            Field cmField = VelocityServer.class.getDeclaredField("cm");
            cmField.setAccessible(true);
            ConnectionManager cm = (ConnectionManager) cmField.get(server);

            ServerChannelInitializerHolder holder = cm.serverChannelInitializer;
            ChannelInitializer<Channel> original = holder.get();

            Field initializerField = ServerChannelInitializerHolder.class.getDeclaredField("initializer");
            initializerField.setAccessible(true);
            initializerField.set(holder, new Hook(plugin, original));
            return original;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.logger().error("快速路径注入失败，已回退到事件模式（功能不受影响，仅吞吐较低）", e);
            return null;
        }
    }

    /** 恢复原初始化器（卸载/重载时的清理路径）。 */
    public static void restore(MikuMOTDPlugin plugin, ProxyServer proxyServer) {
        try {
            VelocityServer server = (VelocityServer) proxyServer;
            Field cmField = VelocityServer.class.getDeclaredField("cm");
            cmField.setAccessible(true);
            ConnectionManager cm = (ConnectionManager) cmField.get(server);
            ChannelInitializer<Channel> current = cm.serverChannelInitializer.get();
            if (current instanceof Hook hook) {
                Field initializerField = ServerChannelInitializerHolder.class.getDeclaredField("initializer");
                initializerField.setAccessible(true);
                initializerField.set(cm.serverChannelInitializer, hook.original);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.logger().warn("恢复通道初始化器失败", e);
        }
    }

    private static final class Hook extends ChannelInitializer<Channel> {

        private final MikuMOTDPlugin plugin;
        private final ChannelInitializer<Channel> original;

        Hook(MikuMOTDPlugin plugin, ChannelInitializer<Channel> original) {
            this.plugin = plugin;
            this.original = original;
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            try {
                INIT_CHANNEL.invoke(this.original, ch);
            } catch (Throwable t) {
                if (t instanceof Error error) {
                    throw error;
                }
                if (t instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(t);
            }
            try {
                ChannelPipeline pipeline = ch.pipeline();
                if (pipeline.get(Connections.FRAME_DECODER) != null) {
                    pipeline.addAfter(Connections.FRAME_DECODER, StatusFastPath.NAME, new StatusFastPath(this.plugin));
                } else {
                    this.plugin.logger().warn("帧解码器缺失，本连接未启用快速路径");
                }
            } catch (Exception e) {
                // 快速路径挂载失败不影响该连接的原生流程
                this.plugin.logger().warn("快速路径挂载失败，本连接走原生流程", e);
            }
        }
    }
}
