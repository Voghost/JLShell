# Link v2 宿主接入（开发中）

本次宿主变更为 Java Link 桌面接入提供两项边界：受限账号请求网关，以及回环路由的 SSH 主机密钥身份。完整 Java 客户端、插件替换和生产 SSH/SFTP 验收仍按聚合工作区的第 8 章推进。

## 账号网关

`AccountService.authenticatedLinkRequest` 继续由宿主保存和续期账号 JWT，插件只收到 JSON。新增 v2 的账号侧请求：`GET /api/v2/link/agents`、`GET /api/v2/link/agents/{agentId}/access-policy`、`POST /api/v2/link/enrollments`、`POST /api/v2/link/node-challenges`、`PUT /api/v2/link/devices/{deviceId}/identity`、`POST /api/v2/link/control-credentials`。既有 v1 插件请求按方法和路径收紧；绝对 URL、查询参数绕过、编码路径、点段与重定向均不得带出账号令牌。

`/api/v2/link/access-requests` 及 session 续订/关闭使用短期 `X-Link-Control-Credential`，不通过带账号 JWT 的宿主代理发送。客户端引擎通过 Website 专用的受限请求适配器访问这些接口。

## SSH 主机密钥

`ConnectionRequest` 新增可选 `HostKeyIdentity`，旧五参数构造器仍可用。`ConnectionRoute` 新增可选 Agent UUID `gatewayId`，旧三参数构造器和 `loopback(host, port, lease)` 保持兼容；新插件应调用四参数 `loopback`。这个扩展需要在宿主代码合入稳定分支后发布 **Plugin SDK 1.5.0**，插件才能以该方法作为直接依赖。

宿主仅用路由地址连接 socket；严格验钥时以 `gatewayId + 原始 SSH 目标地址 + 原始端口` 派生独立的 `known_hosts` 索引，并在首次确认界面显示真实目标与网关。普通直连 SSH 保持原索引。旧插件没有 `gatewayId` 时暂按项目/连接 ID 隔离，不读取或自动信任历史随机回环端口的密钥记录；迁移到新插件后应重新确认一次目标密钥。不同 C 即使后面是相同私网 IP，也不会共享新索引。

取消路由 Future 后若插件稍后才返回回环租约，宿主会立即关闭该迟到租约。SSH 建连失败、会话关闭和重连继续走已有关闭路径。生产发布前仍须完成严格验钥与 SSH/SFTP 实连验收，并确认新 SDK 和宿主最低版本。
