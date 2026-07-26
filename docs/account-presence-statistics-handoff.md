# 账号在线状态与连接数修复交接

## 背景

JLShellWebsite 的账号控制台使用以下统计口径：

- `connectionCount`：该账号所有在线桌面设备上，当前处于 `CONNECTED` 状态的 SSH 会话总数。
- `terminalCount`：最近 20 分钟内完成过心跳或统计上报的唯一 `deviceId` 数量。
- `historicalDeviceCount`：数据库中曾登录过的唯一设备总数，不会随设备离线减少。

网站端已经改为根据 Redis 实时状态返回前两个数，并按 `deviceId` 去重。客户端仍需修正上报来源，否则连接数会把本地终端、失败连接或残留标签页算进去。

## 当前客户端问题

文件：`ui/src/main/java/com/jlshell/ui/view/MainWindow.java`

`reportAccountStats()` 当前使用：

```java
int tabCount = workspaceTabs.getTabs().size();
accountService.updateLiveStats(tabCount);
```

标签页数量不等于 SSH 连接数。它可能包含本地终端、连接中的标签页、失败或已经断开的标签页。

文件：`ui/src/main/java/com/jlshell/ui/service/account/AccountService.java`

1. 周期心跳仅在 `tokenNearExpiry()` 为真时才发送，与接口文档要求的每 15 分钟心跳不一致。
2. `startReportStats()` 首次上报要等待 5 分钟。恢复已保存会话后，应立即上报一次。
3. 网络恢复后需要继续周期上报，不能因为单次异常停止任务。

## 必须修改

### 1. 使用真实 SSH 会话数

通过 `SessionManager.listSessions()` 统计：

```java
long connectionCount = sessionManager.listSessions().stream()
        .filter(session -> session.state() == SessionState.CONNECTED)
        .count();
accountService.updateLiveStats(Math.toIntExact(connectionCount));
```

不要统计 `workspaceTabs`。在以下时机重新计算并上报：

- SSH 会话成功进入 `CONNECTED`。
- 会话进入 `CLOSED` 或 `FAILED`。
- 标签页关闭触发底层会话关闭后。
- 应用启动并验证保存的登录令牌成功后。

### 2. 登录恢复后立即上报

`validateSession()` 成功后，在启动周期任务前或后立即调用一次：

```java
reportStats(liveConnectionCount);
```

首次上报不应等待五分钟。`MainWindow` 应先从 `SessionManager` 计算真实值，再传给 `AccountService`。

### 3. 固定周期保活

- 每 5 分钟调用 `POST /api/v1/account/report-stats`。
- 每 15 分钟调用 `POST /api/v1/account/heartbeat`，不要只在令牌即将过期时调用。
- 心跳成功后原子替换本地令牌。
- 单次网络失败只记录日志并等待下轮重试；`401/404` 才清理本地会话。

### 4. 退出语义

- 正常退出登录调用 `/logout`，服务端立即移除当前会话。
- 应用直接关闭无法保证网络请求完成时，在线状态最多在 20 分钟后自动过期。
- 不要在关闭应用时伪造设备删除；历史设备仍应保留。

## 验收用例

1. 一个桌面端打开两个已连接 SSH 会话：显示 `2` 个连接、`1` 台在线设备。
2. 同一设备重新登录产生新令牌：仍显示 `1` 台在线设备。
3. 打开本地终端：连接数不增加。
4. SSH 连接失败但标签页保留：连接数不增加。
5. 已连接会话断开：下一次即时上报后连接数减少。
6. 重启客户端并恢复已保存登录：启动后数秒内设备变为在线，不等待五分钟。
7. 客户端断网或退出超过 20 分钟：设备显示离线；历史设备数保持不变。

## 相关接口

完整契约见网站仓库 `docs/client-auth-api.md`。关键请求：

```http
POST /api/v1/account/report-stats
Authorization: Bearer <token>
Content-Type: application/json

{"connectionCount": 2, "deviceId": "stable-device-uuid"}
```

`deviceId` 必须本机持久化并跨登录保持稳定，不能每次启动重新生成。
