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
 * <p>Velocity 在绑定端口之前触发插件初始化事件，因此替换
 * {@link ServerChannelInitializerHolder} 中的初始化器即可覆盖所有后续连接。
 * 访问链中的内部字段（{@code VelocityServer#cm} 与
 * {@code ServerChannelInitializerHolder#initializer} 为私有，
 * {@code ConnectionManager#serverChannelInitializer} 为公开字段）通过反射访问——
 * holder 的公开 {@code set()} 已弃用且会打印替换告警。
 * 任何注入失败都返回 null，由调用方转入事件模式兜底。</p>
 *
 * <p>注意：{@code ServerBootstrap.childHandler} 在 {@code bind()} 时就被固化，
 * 之后再替换 holder 不会作用于已监听的端口。因此「快速路径开关」不能靠
 * 注入/撤销注入实现，而必须在 {@link Hook#initChannel} 里按宿主当前配置决定
 * 是否挂载——Hook 只装一次，永久有效。</p>
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
     * 执行注入（幂等：同一插件实例重复调用不会叠加包装）。
     *
     * @return 成功返回包装前的原初始化器；失败返回 null（调用方应保持事件模式）
     */
    public static ChannelInitializer<Channel> inject(MikuMOTDPlugin plugin, ProxyServer proxyServer) {
        try {
            VelocityServer server = (VelocityServer) proxyServer;
            Field cmField = VelocityServer.class.getDeclaredField("cm");
            cmField.setAccessible(true);
            ConnectionManager cm = (ConnectionManager) cmField.get(server);

            ServerChannelInitializerHolder holder = cm.serverChannelInitializer;
            ChannelInitializer<Channel> current = holder.get();
            if (current instanceof Hook hook) {
                if (hook.host == plugin) {
                    // 同一插件实例重复调用：保持现状，避免同连接挂两次快速路径
                    return hook.original;
                }
                // 插件被重新启用：原 Hook 仍指向已停用的旧实例，必须换成新实例包装，
                // 否则新连接会取到旧实例的空运行时并被断开
                current = hook.original;
            }
            Hook hook = new Hook(plugin, current);
            // 只走反射：holder.set() 已弃用且会打印替换告警。字段被移除/改名说明代理内部结构
            // 不再兼容，按契约回退事件模式（外层 catch 记录并返回 null）
            Field initializerField = ServerChannelInitializerHolder.class.getDeclaredField("initializer");
            initializerField.setAccessible(true);
            initializerField.set(holder, hook);
            return current;
        } catch (Throwable e) {
            // 类结构变化（Error 级）也统一回退事件模式，保证插件仍可用
            plugin.logger().error("快速路径注入失败，已回退到事件模式（功能不受影响，仅吞吐较低）", e);
            return null;
        }
    }

    private static final class Hook extends ChannelInitializer<Channel> {

        private final FastPathHost host;
        private final ChannelInitializer<Channel> original;

        Hook(FastPathHost host, ChannelInitializer<Channel> original) {
            this.host = host;
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
                install(ch.pipeline(), this.host);
            } catch (Exception e) {
                // 快速路径挂载失败不影响该连接的原生流程
                this.host.logFastPathFault(ch.remoteAddress(), e);
            }
        }
    }

    /**
     * 按宿主当前配置在这条连接上挂载（或跳过）快速路径。
     *
     * <p>开关在每次新连接建立时读取，因此热重载切换 compat-mode 对之后的所有连接
     * 立即生效——不需要、也无法通过替换通道初始化器来切换（初始化器在端口绑定时已固化）。</p>
     *
     * @return 是否实际挂载了快速路径
     */
    public static boolean install(ChannelPipeline pipeline, FastPathHost host) {
        if (!host.fastPathEnabled()) {
            return false;
        }
        if (pipeline.get(Connections.FRAME_DECODER) == null) {
            // 代理的初始化器一定会装帧解码器；缺失说明这条连接不归它管，跳过即可
            return false;
        }
        pipeline.addAfter(Connections.FRAME_DECODER, StatusFastPath.NAME, new StatusFastPath(host));
        return true;
    }
}
