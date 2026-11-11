# 插件宿主扩展

本次扩展面向后续 SDK 版本。Session 插件和 Program 插件通过 `context.host()` 获取宿主服务，Program 插件的会话贡献通过对应会话的 `PluginContext` 获取相同能力。

## 实施范围

| 审核编号 | 宿主入口 |
| --- | --- |
| 1 | `registerFileAction`，文件面板右键菜单 |
| 2 | `registerPreview`、`openPreview`，文件双击和预览窗口 |
| 3、4 | `terminal(sessionId)` 的 `paste`、`submit`、`sendKey`、`submitBatch` |
| 5、6 | `snapshot`、`subscribe`，屏幕、回滚内容、选择文本和实时输入输出 |
| 7、8 | `viewOptions`、`layout`、`onLayoutChanged` |
| 9 | `COMMAND_STARTED`、`COMMAND_FINISHED`，Shell 命令标记与退出码 |
| 10 | `sessions`、`selectSession`、`onSessionsChanged` |
| 11 | `navigate`，文件面板路径和选择项 |
| 12 | `FileExplorer.stat`、`rename`、`createDirectory`、`readRange`、`upload`、`download` |
| 13 | `registerTextAction`，终端选中文本菜单 |
| 14 | `task`，后台虚拟线程、进度窗口、状态栏、取消 |
| 21 | 已安装插件详情的性能诊断入口，耗时记录和后台任务停止 |
| 24 | `confirm`、`prompt`、`chooseFile`，宿主主题下的 Swing 对话框 |
| 25 | `info`、`capabilities`、`availability`，版本和可选能力查询 |
| 26 | `own`、注册句柄和上下文释放 |
| 27 | `ProgramApiContext.workbench()` 和内置 JSON-RPC 方法 |

## 文件扩展

```java
var host = context.host();
host.registerFileAction(new HostExtensions.FileAction(
        "inspect-text", "检查文本", null, "",
        selection -> selection.files().size() == 1,
        selection -> !selection.files().get(0).directory(),
        selection -> host.openPreview(selection.files().get(0))));
```

文件引用包含路径、名称、大小、目录标记及远程会话 ID。`FileRef.sessionId == null` 表示本地文件。`FileSelection.sessionId` 标识发出菜单操作的宿主会话。

菜单的可见性和启用条件在 EDT 求值，应快速返回。执行回调在 EDT 发起，耗时处理应交给 `task` 或异步文件 API。扩展异常会记录日志。

预览器按注册顺序匹配。`PreviewProvider.create` 在 EDT 调用，返回异步内容结果。插件应在 EDT 创建 Swing 组件，并将文件读取放到后台。实现 `AutoCloseable` 的预览组件在窗口关闭时释放。

文件面板提供默认 UTF-8 文本预览，读取上限为 256 KiB。远程 `readRange` 每次最多读取 4 MiB。`openPreview` 和 `navigate` 使用已打开的文件面板。导航 Future 在目录载入和选中项应用完成后完成。

## 终端

```java
var terminal = context.host().terminal(context.sshSession().orElseThrow().sessionId())
        .orElseThrow();
terminal.paste("ls -la");
terminal.submit("pwd");
terminal.submitBatch(List.of("pwd", "uname -a"), Duration.ofMillis(200), sent -> {
    // 已发送条数。UI 更新需要切换到 EDT。
});
context.host().own(terminal.subscribe(event -> {
    // 后台回调。OUTPUT 为原始流片段，可包含分片 ANSI 转义序列。
}));
```

`paste` 使用终端的 bracketed paste 模式。当前 Shell 未启用此模式时，多行粘贴返回明确错误，可选择 `submitBatch` 执行。`submit` 接受单行命令并发送回车。批次 Future 表示输入已交给终端输入队列，命令完成由 Shell 事件报告。取消批次会停止尚未发送的部分，已发送的输入仍由远程 Shell 处理。

`snapshot(historyLines, maxCharacters)` 在 EDT 和终端缓冲区锁内获取一致快照。历史最多请求 100000 行，文本上限最多为 4000000 字符。`truncated` 标识截断。当前会话保留的回滚内容随终端关闭释放。

实时事件携带递增序号和时间。慢订阅者可能出现序号缺口，可重新请求快照恢复状态。输入事件是原始终端输入，其中可能含有敏感数据。编辑中的命令草稿需要插件结合 Shell 语义处理。

SSH Bash/Zsh 初始化会增加 OSC 133 命令标记，保留原有提示符钩子。Bash 的开始事件依赖 PS0，需要 Bash 4.4 或更高版本。`commandTrackingAvailable()` 在兼容 Shell 完成初始化或已观察到开始标记时返回 true。其他 Shell 可通过兼容 OSC 133 的配置提供事件。输出和命令事件使用同一递增序列，插件可按开始与结束边界关联输出。

## 布局

在 `openTab` 前调用 `viewOptions` 声明首选尺寸和最小尺寸，单位为逻辑像素。当前支持 `RIGHT`、`BOTTOM_LEFT`、`BOTTOM_RIGHT`。

已有的工具窗口位置优先采用保存值。现有宿主布局使用共享的侧栏宽度和底部高度，已保存布局时优先采用这些尺寸。`layout()` 在组件建立并进入布局后提供值，监听器回调在 EDT 执行。

## 生命周期与任务

扩展注册、终端订阅、批次和后台任务由上下文持有。插件停用、页面所属上下文销毁时释放资源。其他资源可交给 `own`，释放采用逆序，单个资源抛错后仍会继续释放后续资源。

后台任务使用虚拟线程。任务应定期检查 `TaskContext.cancelled()` 并响应线程中断。250 ms 后仍在运行的任务显示进度窗口。状态栏沿用现有高度，展示后台任务进度。性能诊断显示本次运行保留的最近 1000 条结束阶段记录，并提供停止插件后台任务的入口。

## Program API

`ProgramApiContext.workbench().invoke(operation, arguments)` 提供工作台操作，内置本地 JSON-RPC 注册同名方法并沿用现有认证。

| 方法 | 参数 |
| --- | --- |
| `workbench.sessions` | `{}` |
| `workbench.selectSession` | `sessionId` |
| `terminal.snapshot` | `sessionId`、可选 `historyLines`、`maxCharacters` |
| `terminal.paste` | `sessionId`、`text` |
| `terminal.submit` | `sessionId`、`command` |
| `terminal.key` | `sessionId`、`key`，例如 `ENTER`、`INTERRUPT` |
| `terminal.batch` | `sessionId`、`commands`、可选 `intervalMs` |
| `file.list`、`file.stat` | `sessionId`、`path` |
| `file.readRange` | `sessionId`、`path`、可选 `offset`、`length`，返回 Base64 |
| `file.rename` | `sessionId`、`source`、`target` |
| `file.mkdir` | `sessionId`、`path`、可选 `recursive` |
| `file.navigate` | `sessionId`、`remote`、`directory`、可选 `selectedPaths` |
| `task.list` | `{}` |
| `task.cancel` | `taskId` |

已有上下文实现运行于新版 SDK 时，新增 default 方法提供不可用实现，插件应查询可选能力。旧插件可在新版宿主中继续运行。调用新增接口的新插件需要声明包含该 SDK 的最低宿主版本。


## 跨会话工作区

Program 插件新增 `context.workspace()`，Program API SPI 使用 `ProgramApiContext.workspace()`。
`ProgramWorkspaceService` 提供连接元数据检索、会话打开与复用、单选/多选/角色选择、就绪等待、显式终端定位、重连、生命周期事件、文件传输与跨会话任务。

- `sessionId` 标识一次会话，`terminalId` 标识一个终端实例。重连返回新标识。存在多个终端时必须明确提供 `terminalId`。
- `TAB` 打开可见终端，`BACKGROUND` 打开 SSH 传输。后台会话通过 `reveal` 提升为标签后才能输入终端，本地 Shell 需要 TAB。
- `CONNECTED` 为传输就绪，`TERMINAL` 为终端注册，`SHELL` 为 Shell 集成初始化确认。命令结束等待需要读取 `commandTracking` 能力。
- `enqueue` 为每个终端提供 FIFO 队列，支持 PASTE、KEY、SUBMIT、BATCH。`awaitCommand` 适用于 SUBMIT/BATCH，非零退出码失败，超时从入队开始。
- `operation`、`awaitOperation`、`cancelOperation` 管理输入与文件传输。取消停止尚未提交的工作，已发送命令可能继续运行。
- `createJob` 为每个步骤绑定会话及可选终端，向操作传入 `jobId`、`stepId` 后自动更新进度。`updateStep` 支持调用方执行的步骤。
- 工作区 `events` 最近保留 512 条。终端 `terminalEvents` 最近保留 256 条与 256 Ki 字符。序号缺口或 truncated 需要重新读取快照。
- Program 插件停用时自动注销订阅并取消其未结束的任务、操作。用户会话由宿主管理。

本地 RPC 使用相同的服务和队列。新增 25 个方法及参数 Schema 见 `WorkspaceRpcCatalog`，既有 `terminal.*` 输入方法也进入共享队列。`workbench.sessions` 返回完整工作区元数据。所有 RPC 沿用本机 Bearer Token 认证。

网站文档新增 SDK 2.1.0 预览，包含 Plugin / Program 中英文版本、变更记录、Raw Markdown 和 JSON-RPC 调用示例。SDK 2.1.0 公共 Maven 包发布为独立后续流程。

## Harness Agent 开发补充

- `workspace.context(ContextRequest)` 与 RPC `agent.context` 提供 schemaVersion=1 的公共程序上下文。包括左侧选中连接、活动会话/终端、窗口/项目/主题、会话连接时间与持续秒数、最近业务错误、任务及进程摘要。终端内容通过 includeTerminalSnapshot 按需查询。
- `files(sessionId).readVersioned/write/applyPatch` 支持本地与 SSH 文件的 SHA-256 版本校验、同目录临时写入、原子替换和备份。精确补丁面向 UTF-8，每个 oldText 唯一匹配。单次编辑最大 4 MiB。远端原子覆盖需要对应 SFTP 能力，外部写入仍存在最后校验与重命名之间的乐观并发窗口。
- `workspace.processes()` 提供 argv、工作目录、环境、有界 stdout/stderr、独立流事件、退出码、超时和通道级取消。远端编码面向 POSIX Shell，本地使用 ProcessBuilder。取消进入 CANCELLING，通道清理后完成。输出缓存受全局 8 Mi 字符预算控制。
- 任务步骤及 connectionId 自动写入宿主 SQLite 的 agent_jobs 表。`checkpoint(jobId, expectedRevision, dataJson)` 完成时检查点已提交。`resumeJob` 要求为未成功步骤绑定当前会话，重启后未结束步骤为 INTERRUPTED，成功步骤保留。
- 新增 12 个对应 RPC，参数与示例统一维护在 WorkspaceRpcCatalog，网站 SDK 2.1.0 预览文档同步提供中英文及 Raw Markdown。

## SDK 2.1.0 菜单分组

`HostExtensions.FileAction` 和 `TextAction` 默认使用 `MenuPlacement.PLUGIN_SUBMENU`。
宿主按插件 ID 隔离分组，并使用插件的本地化名称显示 `插件名称 → 功能1、功能2`。
旧构造函数继续可用，自动采用二级菜单。传入最后一个参数 `MenuPlacement.TOP_LEVEL`
可显式展示为一级菜单。文件动作的 `group` 用于组内分隔，子菜单保留宿主圆角与键盘导航。
终端在没有选中文本时也评估动作，插件可通过 `visible` 决定是否展示。
