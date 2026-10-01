# MikuMOTD

## 声明

本项目为 MikuMC 服务器原创插件，公开给大众免费使用，MikuMC 服务器与作者 JunXieX 享有项目著作权，本项目非开源项目，请注意。

MikuMC 系列插件交流群：1105054380

---

面向 Velocity 代理的高性能 MOTD 插件。状态查询（服务器列表 ping）在 Netty 字节层直接完成：响应帧预渲染为字节、零拷贝分发，整个 ping 不进入代理的会话处理，不做任何逐次解码、序列化与字符串操作。

## 功能

- **字节级快速路径**：响应包在配置加载时一次性预渲染，ping 路径上仅做一次零拷贝切片后直接写出，负载低于不装任何插件的原生代理
- **多 MOTD 随机轮换**：描述与图标做笛卡尔积生成模板池，每次 ping 随机取一条
- **按协议版本段的 MOTD**：为不同客户端版本段（如 `757-800`）配置独立 MOTD
- **按域名的 MOTD**：同一代理不同域名（`play.example.com:25565`）显示不同 MOTD，匹配不区分大小写
- **假在线人数**：固定加值 + 百分比加值
- **最大人数**：固定值或「当前人数 + N」两种模式
- **玩家列表（sample）**：默认显示真实在线玩家的 ID 与名字，也可切换为自定义静态展示行
- **维护模式**：独立 MOTD、可覆盖显示人数、可选登录直接拒绝（带 IP 白名单）、可选隐藏真实协议号
- **占位符**：描述与玩家列表支持 `{online}`、`{max}` 与换行符 `{NL}`，人数变化时自动重渲染
- **多文本格式**：MINIMESSAGE / LEGACY_AMPERSAND / LEGACY_SECTION / JSON
- **图标规范化**：任意尺寸 PNG 自动缩放到 64x64，可选重编码压缩
- **热重载**：`/mikumotd reload` 即时生效，重载失败时保留旧配置继续运行

## 安装

1. 服务器要求：Velocity 4.x（按 4.2.0 构建），Java 25 及以上
2. 将 `MikuMOTD-x.y.z.jar` 放入 `plugins/` 目录，启动代理
3. 首次启动生成 `plugins/mikumotd/config.conf`，按需修改后 `/mikumotd reload`

## 命令与权限

| 命令 | 说明 | 权限节点 |
|---|---|---|
| `/mikumotd info` | 查看运行模式、维护状态与在线人数 | `mikumotd.command.info` |
| `/mikumotd reload` | 重载配置 | `mikumotd.command.reload` |
| `/mikumotd maintenance [on\|off\|toggle]` | 切换维护模式（运行时生效，不写回配置文件） | `mikumotd.command.maintenance` |

## 配置概览

```hocon
general {
    # 人数刷新间隔（毫秒）
    update-interval-ms=3000
    # true：直写连接出站缓冲（最快）；false：走 Netty 标准 write 路径
    direct-write=true
    # 强制事件模式（快速路径不可用时插件也会自动回退，仅性能不同）
    compat-mode=false
    text-format=MINIMESSAGE
    # 图标重编码质量 0.0~1.0，越小文件越小；-1 关闭
    png-quality=0.0
}
players {
    max-count-type=FIXED    # FIXED：固定最大人数；ADD：当前人数 + max-count
    max-count=1000
    fake-online-fixed=0
    fake-online-percent=0
}
motd {
    version-name="MikuMOTD"
    descriptions=["<bold><gradient:#40c4ff:#a78bfa>MikuMOTD</gradient></bold>"]
    favicons=["server-icon.png"]   # 支持文件路径或 data:image/png;base64,... 
    player-list=["<gray>由</gray> <aqua>MikuMC</aqua> <gray>驱动</gray>"]
    player-list-source="real"      # real（默认）：显示真实在线玩家的 ID 与名字；static：显示 player-list 静态行
}
# 可选节，节点结构与 motd 相同：
# protocol-motd { "757-800" { ... } }
# domain-motd  { "play.example.com:25565" { ... } }
maintenance {
    enabled=false
    kick-on-join=false
    kick-whitelist=["127.0.0.1"]
    kick-message="<red>服务器维护中，请稍后再来</red>"
    override-online=-1
    override-max-online=-1
    motd { ... }
}
```

## 性能

在 GitHub Actions 的同一台 runner 上五场景交替轮测（3 轮取中位数），对比对象含现场从源码构建的 FastMOTD 与 MiniMOTD（两者使用各自默认配置，MikuMOTD 场景使用对齐精简配置）：

| 场景 | 平均延迟（µs/完整 ping） | QPS（8 线程 × 6s） |
|---|---|---|
| **MikuMOTD 快速路径** | **272** | **22 488** |
| FastMOTD | 287 | 22 161 |
| 裸代理（无插件） | 374 | 19 399 |
| MikuMOTD 事件模式 | 370 | 18 906 |
| MiniMOTD | 479 | 15 062 |

快速路径延迟低于 FastMOTD 约 5%、低于不装任何插件的原生代理约 27%、低于 MiniMOTD 约 43%；QPS 高于 FastMOTD 约 2%、高于裸代理约 16%、高于 MiniMOTD 约 49%；事件模式兜底与裸代理相当。共享 runner 上负载有漂移，表内数据仅供同批次横向对比，完整方法与原始数据见仓库 Actions 的 **Bench** 工作流（可随时手动触发复测）。

## 注意事项

- 快速路径直接拦截状态查询连接，`velocity.toml` 的 `ping-passthrough` 对这些连接不再生效（自定义 MOTD 本就是替代行为）；登录与传输连接完全透传，代理原生行为不受影响
- 状态查询连接不触发 `ConnectionHandshakeEvent` 等代理握手事件（登录连接不受影响）；依赖该事件做防机器人等用途的插件不受此插件干扰
- 注入依赖 Velocity 内部实现，若未来代理版本变更导致注入失败，插件会自动回退到事件模式并输出日志，功能完整可用
- 游戏内切换的维护模式不写回配置文件，重启后回到配置文件的 `maintenance.enabled` 值
