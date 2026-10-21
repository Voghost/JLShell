# 程序启动与插件加载耗时排查

新增耗时日志默认启用，关键字为 `Performance`，与异常日志一起写入现有的滚动日志：

- Windows：`%USERPROFILE%\.jlshell\logs\jlshell.log`
- macOS / Linux：`~/.jlshell/logs/jlshell.log`

安装包含本改动的桌面版本后，重启一次程序，再首次打开待排查插件即可收集。
不需要升级插件 SDK，也不需要重新编译旧插件。

## 日志字段

每次启动、插件扫描、插件激活、点击打开页面都分配独立 `traceId`。
编号带进程和启动时间前缀，避免多次启动的日志混淆；`parentId` 关联子操作。
同一次操作跨 FX、EDT 或后台线程时仍保留编号，线程名在日志行前缀中。

| 字段 | 含义 |
| --- | --- |
| `operation` / `subject` | 操作类型及插件 ID、入口类或 JAR 文件名 |
| `stage` | 阶段名称；`#数字` 区分同一操作中的阶段实例 |
| `event=start` | 阶段即将执行，可用于寻找卡住后没有完成记录的阶段 |
| `event=end` | 阶段结束，`outcome` 表示成功、失败、复用或提前关闭等结果 |
| `event=mark` | 一个里程碑，只看累计时间，`durationMs=0` |
| `durationMs` | 本阶段的墙钟耗时，单位毫秒，使用单调时钟 |
| `elapsedMs` | 从当前 `traceId` 开始累计的耗时 |
| `slow=true` | 耗时达到慢阶段阈值，默认 500 毫秒，使用 WARN 输出 |
| `errorType` | 异常类型；耗时日志不复制异常消息或页面内容 |

下面是格式示例，数值仅用于说明：

```text
Performance traceId=run-7 parentId=- operation=plugin-open subject=example.tools stage=fx-queue#1 event=end outcome=success durationMs=15 elapsedMs=16 slow=false errorType=-
Performance traceId=run-8 parentId=run-7 operation=plugin-activate subject=example.tools stage=plugin-code#4 event=end outcome=success durationMs=4210 elapsedMs=4218 slow=true errorType=-
Performance traceId=run-7 parentId=- operation=plugin-open subject=example.tools stage=total event=end outcome=swing-first-paint durationMs=4390 elapsedMs=4390 slow=true errorType=-
```

该例表示耗时主要在插件 `activate()`，并非 FX 队列等待。

## 程序启动

`operation=startup` 从桌面 Java 入口开始，覆盖：

1. `splash-first-frame`：启动窗口首次绘制等待。
2. `javafx-toolkit-bootstrap`：JavaFX 启动至 `Application.init()`。
3. `app-context`：服务容器创建；子操作进一步拆分数据库连接池、JDBI、数据库 schema、凭据密钥、核心服务、语言、配色、主题、SSH/SFTP、RPC、保险库迁移、UI 服务、Program API Provider、Program 插件扫描和激活、主窗口控制器。
4. `fx-scene-model`、`swing-window-model`：工作台模型构建。
5. `swing-theme-edt-queue`、`swing-theme-install`：异步主题准备的队列等待与执行。
6. `swing-workbench-edt-queue`、`swing-theme-confirm`、`swing-workbench-create`、`swing-window-show`：Swing 工作台的队列等待、主题确认、构建和打开。
7. `workbench-first-paint`：可见且尺寸有效的 Swing 工作台完成首次子组件绘制。

启动总耗时以 Swing 工作台首次绘制为终点，不把 `setVisible()` 返回误当作首次绘制。
JavaFX 对照入口以 `fx-window-shown` 结束，其口径是 `Stage.show()` 返回。
启动失败输出 `outcome=failed`，具体堆栈仍查看附近的原有异常日志。

范围不包含 JVM / 原生启动器进入 Java 入口之前的耗时。首次绘制也不代表之后异步加载的连接列表、更新检查、账号网络验证都已经结束。
主题准备可能与服务装配并行，因此各阶段、父子操作或多个线程的耗时不能简单相加。

## 插件扫描、激活与打开

- `plugin-scan`（session / program）：扫描总耗时、目录解析、JAR 列举、SPI 服务发现。
- `plugin-jar` / `program-plugin-jar`：每个 JAR 的包校验、类加载器创建及其中的 SPI 加载。
- `plugin-provider` / `program-plugin-provider`：每个入口类的构造/静态初始化、元数据或上下文创建；`plugin-id-*` 里程碑对应插件 ID。
- `plugin-activate` / `program-plugin-activate`：权限检查、运行时服务、插件自己的 `activate()`，以及 Program API Provider 激活。
- `plugin-open`：每次点击打开页面，覆盖 FX 队列、上下文和激活、页面请求、EDT 队列、Swing 页面工厂、JavaFX 桥接与 Scene 创建。

原生 Swing 页面以 `swing-first-paint` 结束；旧 JavaFX 页面单独记录 `fx-scene-attached` 和 `fx-first-layout`，以 `fx-content-laid-out` 结束。
后者表示内容进入显示中的 Scene 并完成有效尺寸布局，不代表 GPU 已提交画面，也不代表插件后续异步业务数据全部加载完毕。
已有页面再次选择会输出 `reused-tab`。打开过程中关闭或会话释放会记录提前结束。

排查顺序：先看 `total` 找到慢操作，再按该 `traceId` 及其子 `parentId` 查看最大的阶段耗时。
若 `activate()` 返回很快，但 `swing-view-request` / `fx-view-request` 很晚，通常是插件内部异步初始化的间隔；若只出现 `activation-returned` 后没有页面请求，宿主仍在等插件提供页面。
宿主无法细分私有插件内部的方法、网络请求或数据库调用，这部分需在插件自身补充日志。
如果进程卡住，最后一个只有 `start` 而没有 `end` 的阶段值得重点检查；日志片段或轮转截断也可能造成未配对。

## 快速查找与配置

Windows PowerShell：

```powershell
Select-String -Path "$env:USERPROFILE\.jlshell\logs\jlshell.log" -Pattern 'Performance.*event=end.*slow=true'
```

macOS / Linux：

```bash
rg 'Performance.*event=end.*slow=true' ~/.jlshell/logs/jlshell.log
```

JVM 参数可调整阈值或关闭本功能：

```text
-Djlshell.performance.slowMillis=1000
-Djlshell.performance.enabled=false
```

使用现有日志滚动策略，不增加定时采样线程，不在每次重绘时反复输出日志。
新增日志仅使用插件标识、入口类/JAR 文件名、阶段、异常类型和时间，不增加密码、令牌、终端命令、连接地址或页面业务数据的记录。
