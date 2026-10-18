package com.mikumc.motd.inject;

import io.netty.buffer.ByteBuf;

/**
 * 快速路径所需的外部能力（由主插件实现）。
 *
 * <p>把这几项收成一个窄接口，使字节层可以在没有完整代理环境的情况下
 * 用真实 Netty 管线做端到端测试——握手/状态请求/心跳的分包与状态切换
 * 都依赖真实解码器行为，单靠纯函数测试覆盖不到。</p>
 */
public interface FastPathHost {

    /**
     * 当前是否启用快速路径逻辑。
     *
     * <p>开关必须由宿主实时读取，而不能靠替换通道初始化器实现：
     * Velocity 在绑定端口时就把初始化器固化进 {@code ServerBootstrap.childHandler}，
     * 之后替换 holder 对已监听端口不再生效。</p>
     */
    boolean fastPathEnabled();

    /** 取一条当前协议号对应的完整响应帧（含长度前缀，引用计数已加一）。未就绪返回 null。 */
    ByteBuf selectResponse(int protocol);

    /** 是否直接写入连接出站缓冲（跳过 Netty 标准 write 路径）。 */
    boolean directWrite();

    /** 是否允许非标准 ping 顺序（重复状态请求）。 */
    boolean allowImproperPings();

    /** 记录一次状态握手（受配置开关控制）。 */
    void onStatusHandshake(java.net.SocketAddress remote, int protocol);

    /** 记录一次非标准 ping 序列（受配置开关控制）。 */
    void logImproperPing(java.net.SocketAddress remote, String detail);

    /** 快速路径内部异常（限速日志）。 */
    void logFastPathFault(java.net.SocketAddress remote, Throwable cause);
}
