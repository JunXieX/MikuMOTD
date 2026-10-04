# MikuMOTD

## 声明

本项目为 MikuMC 服务器原创插件，公开给大众免费使用，MikuMC 服务器与作者 JunXieX 享有项目著作权，本项目非开源项目，请注意。

MikuMC 系列插件交流群：1105054380

---

面向 Velocity 代理的高性能 MOTD 插件。状态查询（服务器列表 ping）在 Netty 字节层直接完成：响应帧预渲染、零拷贝分发，整个 ping 不进入代理的会话处理，不做任何逐次解码、序列化与字符串操作。

## 功能

- **自定义服务器图标（logo）**：PNG 文件路径或 base64 data URL，任意尺寸自动缩放为 64x64
- **MOTD 文本**：支持 MiniMessage 全语法（渐变、粗体等），`{NL}` 换行
- **人数上限**：可覆盖显示值，缺省沿用 velocity.toml 的 show-max-players
- **真实玩家列表**：与原生代理一致的悬停玩家展示（按 UUID 排序，最多 12 行，随刷新周期自动更新）
- **MikuVanish 联动**：安装 MikuVanish 后自动从在线人数与玩家列表中剔除隐身玩家（软依赖，未安装则无感知）
- **热重载**：/mikumotd reload 即时生效，重载失败时保留旧配置继续运行

真实玩家列表与在线人数恒为代理真实数据，不做任何伪造。

## 安装

1. 服务器要求：Velocity 4.x（按 4.2.0 构建），Java 25 及以上
2. 将 MikuMOTD-x.y.z.jar 放入 plugins/ 目录，启动代理
3. 首次启动生成 plugins/MikuMOTD/config.conf，按需修改后 /mikumotd reload

## 命令与权限

| 命令 | 说明 | 权限节点 |
|---|---|---|
| /mikumotd info | 查看运行模式、人数上限与在线人数 | mikumotd.command.info |
| /mikumotd reload | 重载配置 | mikumotd.command.reload |

## 配置

```hocon
# 服务器图标：PNG 文件路径（相对插件目录或绝对路径）、base64 data URL 或 "none"
logo="server-icon.png"
# MOTD 文本（MiniMessage），{NL} 为换行
motd="<bold><gradient:#40c4ff:#a78bfa>MikuMOTD</gradient></bold>{NL}<gray>由</gray> <aqua>MikuMC</aqua> <gray>驱动</gray>"
# 显示的人数上限；0 = 使用 velocity.toml 的 show-max-players
max-players=0
# 在线人数与真实玩家列表的刷新间隔（毫秒）
update-interval-ms=1000
# true：直写连接出站缓冲（最快）；false：走 Netty 标准 write 路径
direct-write=true
# 强制使用事件模式（快速路径注入失败时插件会自动回退，仅性能不同）
compat-mode=false
# log-pings / log-improper-pings / allow-improper-pings：诊断与安全选项，默认关闭
```

## 性能

在 GitHub Actions 的同一台 runner 上五场景交替轮测（3 轮取中位数），对比对象含现场从源码构建的 FastMOTD 与 MiniMOTD（两者使用各自默认配置，MikuMOTD 场景使用对齐精简配置）：

| 场景 | 平均延迟（µs/完整 ping） | QPS（8 线程 × 6s） |
|---|---|---|
| **MikuMOTD 快速路径** | **358** | **16 641** |
| FastMOTD | 386 | 15 992 |
| 裸代理（无插件） | 492 | 13 828 |
| MikuMOTD 事件模式 | 460 | 13 501 |
| MiniMOTD | 601 | 10 501 |

快速路径延迟低于 FastMOTD 约 7%、低于不装任何插件的原生代理约 27%、低于 MiniMOTD 约 40%；QPS 高于 FastMOTD 约 4%、高于裸代理约 20%、高于 MiniMOTD 约 59%；事件模式兜底与裸代理相当。共享 runner 上负载有漂移，表内数据仅供同批次横向对比，完整方法与原始数据见仓库 Actions 的 **Bench** 工作流（可随时手动触发复测）。

## 注意事项

- 快速路径直接拦截状态查询连接，velocity.toml 的 ping-passthrough 对这些连接不再生效（自定义 MOTD 本就是替代行为）；登录与传输连接完全透传，代理原生行为不受影响
- 状态查询连接不触发 ConnectionHandshakeEvent 等代理握手事件（登录连接不受影响）；依赖该事件做防机器人等用途的插件不受此插件干扰
- 注入依赖 Velocity 内部实现，若未来代理版本变更导致注入失败，插件会自动回退到事件模式并输出日志，功能完整可用
