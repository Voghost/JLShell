# JLShell

[English](README.md)

基于 JavaFX 构建的现代化跨平台 SSH/SFTP 客户端，拥有 IDE 风格界面、SFTP 文件浏览器、插件间 RPC 通信系统以及外部 JSON-RPC API。

![Java](https://img.shields.io/badge/Java-21-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)
![JDBI](https://img.shields.io/badge/JDBI-3-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 功能特性

### 终端
- 基于 JediTerm 的完整终端模拟，支持垂直/水平分屏
- 305+ 内置配色方案（Dracula、Solarized、One Half 等）+ 自定义配色编辑器
- 字体、字号、行距、连字控制
- 本地 Shell 会话（zsh / bash / cmd.exe）

### SFTP 文件浏览器
- 双面板文件管理器，本地和远程均有目录树 + 文件列表
- 上传、下载、重命名、删除、新建目录
- 跟随终端目录（OSC 7 提示钩子注入）

### 连接管理
- 将连接组织到**项目**和**文件夹**中（嵌套，最多 5 层）
- 主机密钥校验模式（自动信任 / 首次确认 / 严格）
- **凭据库** — AES-256-GCM 加密存储，可选主密码保护
- **导入** — 批量导入 MobaXterm INI、Xshell .xsh 或手动 JSON

### 插件系统
- 将 JAR 文件放入 `~/.jlshell/plugins/` 即可扩展应用
- 插件获得完整的 SSH 会话访问权限：命令执行、SFTP、交互式会话
- **插件间 RPC** — 插件声明能力并可互相调用
- 内置 Script Snippets 示例插件 + System Monitor 系统监控插件

### 外部 API（JSON-RPC）
- 在 `127.0.0.1` 上提供 HTTP JSON-RPC 2.0 服务，Bearer Token 认证
- 创建连接、运行命令、调用插件能力 — 全部可通过外部工具操作
- 未来 AI / MCP 集成的基础
- 在 **偏好设置 → API** 中启用/配置

### 外观
- 深色和浅色主题，运行时切换
- 英文和简体中文，偏好设置中切换（需重启）

---

## 截图

> 即将推出

## 下载

预构建的独立 JRE 安装包（约 50 MB，无需安装 JDK）：

| 平台 | 下载 | 运行方式 |
|------|------|----------|
| macOS | `JLShell-x.x.x-mac.zip` | 解压 → 双击 `JLShell.app` |
| Linux | `JLShell-x.x.x-linux.tar.gz` | 解压 → `./JLShell.sh` |
| Windows | `JLShell-x.x.x-win.zip` | 解压 → 双击 `JLShell.vbs` |

→ [最新版本](../../releases/latest)

## 从源码构建

**要求：** JDK 21、Maven 3.9+

```bash
# 本地运行（当前平台）
mvn install -DskipTests -q && mvn javafx:run -pl app

# 构建当前平台的分发包
./build-dist.sh

# 构建所有平台（需要各平台的 JDK 21）
JDK21_LINUX=/path/to/linux-jdk21 \
JDK21_WIN=/path/to/win-jdk21 \
./build-dist.sh --all
```

输出在 `dist/` 目录。

---

## 插件开发

### 快速开始

实现 `plugin-api` 模块中的 `JlShellPlugin` 接口：

```java
public class MyPlugin implements JlShellPlugin, PluginView {

    @Override public String id()          { return "com.example.my-plugin"; }
    @Override public String displayName() { return "My Plugin"; }
    @Override public String version()     { return "1.0.0"; }
    @Override public boolean requiresSshSession() { return true; }

    @Override
    public void activate(PluginContext ctx) { /* 会话打开时调用 */ }

    @Override
    public void deactivate() { /* 清理资源 */ }

    @Override
    public Node createView(PluginContext ctx) {
        Button btn = new Button("运行 df -h");
        btn.setOnAction(e ->
            ctx.sshSession().ifPresent(ssh ->
                ssh.commandExecutor().execute("df -h")
                   .thenAccept(out -> Platform.runLater(() -> System.out.println(out.stdout())))
            )
        );
        return btn;
    }
}
```

通过 `META-INF/services/com.jlshell.plugin.api.JlShellPlugin` 注册，打包为 fat JAR，放入 `~/.jlshell/plugins/`。下次启动时插件会出现在 **插件** 标签页中。

完整示例请参考 `plugins/plugin-demo/`。

### 插件上下文 API

`PluginContext` 提供以下访问能力：

| API | 描述 |
|-----|------|
| `sshSession()` | 当前 SSH 会话（命令执行、SFTP、交互式 Shell） |
| `capabilityRegistry()` | 注册能力供其他插件或外部 API 调用 |
| `capabilityBus()` | 调用其他插件的能力（旧版宿主返回 `null`） |
| `storage()` | 插件持久化键值存储，按插件隔离（旧版宿主返回 `null`） |
| `openTab()` / `closeTab()` | 管理工作区中的插件标签页 |
| `showNotification()` | 显示应用内通知 |
| `themeNameProperty()` | 可观察的主题变化 |
| `localeProperty()` | 可观察的语言变化 |

### SSH 会话能力

`SshSessionContext` 暴露：

| 能力 | 描述 |
|------|------|
| `commandExecutor()` | 执行一次性 Shell 命令 |
| `interactiveCommandExecutor()` | 多步交互式会话（sudo、2FA 等） |
| `fileExplorer()` | SFTP 操作（列表、读取、写入、删除） |

### 插件持久存储

插件可以通过 `PluginStorage` 持久化数据（配置、缓存、用户偏好）— 基于应用内置 SQLite 的简单键值存储，按插件 ID 自动隔离命名空间，插件无需引入 SQLite/JDBI 依赖。

```java
PluginStorage store = ctx.storage();
if (store != null) {
    // 读写
    store.put("lastDirectory", "/var/log");
    String dir = store.get("lastDirectory");              // "/var/log"
    String fallback = store.get("missing", "default");    // "default"

    // 列出、删除、清空
    Set<String> keys = store.keys();     // 仅返回当前插件的 key
    store.remove("lastDirectory");
    store.clear();                       // 清除当前插件所有数据
}
```

数据存储在 `plugin_storage` 表中，以 `(plugin_id, key)` 为主键 — 每个插件只能读写自己的数据，key 不会跨插件冲突。数据在应用重启后仍然保留。

> **向后兼容：** 旧版宿主中 `ctx.storage()` 返回 `null`。使用前务必检查，与 `capabilityBus()` 模式一致。

### 插件间 RPC

插件可以声明**能力（Capability）**，供其他插件或外部 API 调用：

```java
@Override
public void activate(PluginContext ctx) {
    ctx.capabilityRegistry().register(
        Capability.builder("readConfig")
            .description("读取远程文件并返回其内容。")
            .requiresSession(true)
            .handler((args, capCtx) -> {
                String path = args.getAsJsonObject().get("path").getAsString();
                return capCtx.sshSession().orElseThrow()
                    .fileExplorer().readFile(path)
                    .thenApply(bytes -> {
                        JsonObject result = new JsonObject();
                        result.addProperty("path", path);
                        result.addProperty("content", new String(bytes, UTF_8));
                        return result;
                    });
            })
            .build());
}
```

能力按 `(sessionId, pluginId, capabilityName)` 三元组路由，与外部 API 共用同一个 `CapabilityBus` — 外部调用者可直接调用。

### 调用其他插件的能力

使用 `ctx.capabilityBus()` 在插件内调用另一个插件的能力：

```java
CapabilityBus bus = ctx.capabilityBus();
if (bus != null) {
    RpcRequest req = new RpcRequest(
        sessionId, "com.jlshell.sysmon", "getMetrics", null, "req-1");
    bus.invoke(req).thenAccept(resp -> {
        if (resp.error() == null) {
            JsonObject metrics = resp.result().getAsJsonObject();
            double cpu = metrics.get("cpuUsage").getAsDouble();
            // ...
        }
    });
}
```

### 实际示例：Script Snippets ↔ System Monitor

内置的两个 demo 插件演示了完整的 RPC 通信流程：

1. **System Monitor** 注册 `getMetrics` 能力，通过 SSH 采集远程服务器的 CPU、内存、网络、磁盘指标
2. **Script Snippets** 提供 "📊 Fetch Metrics" 按钮，通过 `CapabilityBus` 调用 System Monitor 的 `getMetrics`
3. 两个能力同时可通过 HTTP API 被外部调用：

```bash
# 获取 System Monitor 插件的系统指标
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"capability.invoke","params":{"sessionId":"...","pluginId":"com.jlshell.sysmon","capability":"getMetrics"}}' \
     http://127.0.0.1:$PORT/rpc

# 通过 Script Snippets 插件读取远程文件
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"capability.invoke","params":{"sessionId":"...","pluginId":"com.jlshell.demo.script-snippets","capability":"readConfig","args":{"path":"/etc/hostname"}}}' \
     http://127.0.0.1:$PORT/rpc
```

---

## 外部 API

JLShell 在 `127.0.0.1` 上提供 JSON-RPC 2.0 服务。在 **偏好设置 → API** 中启用。

### 认证

Bearer Token 存储在 `~/.jlshell/api.token`（POSIX 系统文件权限 600）。请求时在 `Authorization` 头中传入：

```
Authorization: Bearer <token>
```

### 可用方法

| 方法 | 描述 |
|------|------|
| `session.connect` | 建立 SSH 连接 |
| `session.disconnect` | 关闭会话 |
| `session.list` | 列出活动会话 |
| `session.info` | 获取会话详情 |
| `command.run` | 执行远程命令 |
| `capability.list` | 列出会话的插件能力 |
| `capability.invoke` | 调用插件能力 |
| `api.token` | 获取当前 API Token |
| `api.methods` | 列出可用 API 方法 |

### 示例

```bash
# 列出活动会话
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"session.list"}' \
     http://127.0.0.1:$PORT/rpc

# 执行命令
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"command.run","params":{"sessionId":"...","command":"uname -a"}}' \
     http://127.0.0.1:$PORT/rpc
```

能力调用的完整示例见上方 [插件间 RPC → 实际示例](#实际示例script-snippets--system-monitor)。

---

## 连接导入

在 **偏好设置 → 导入** 中从其他 SSH 客户端或手动批量导入连接：

| 来源 | 格式 | 说明 |
|------|------|------|
| MobaXterm | `.ini` | 解析 `[SessionSettings\...]` 段；保留目录层级 |
| Xshell | `.xsh` | 单文件或目录批量导入；中文版 GBK 编码自动回退 |
| 手动 | JSON | `[{name, host, port, user, authType, ...}]`；也支持表格行编辑 |

密码不会导入（后续通过编辑连接补充）。

---

## 凭据库

使用内置凭据库加密管理凭据：

- **AES-256-GCM** 加密，12 字节 IV
- 双模式：系统密钥（透明）或用户主密码（PBKDF2，310K 迭代）
- 存储密码、私钥和密钥内容
- 项目级作用域
- 运行时锁定/解锁

---

## 项目结构

```
jlshell-parent
├── app            — 应用入口，AppContext（手动 DI），打包
├── api-server     — 外部 JSON-RPC API 服务（JDK HttpServer）
├── core           — 共享领域模型和接口
├── data           — JDBI DAO，SQLite 持久化，AES-256-GCM 凭据加密
├── ssh            — 基于 SSHJ 的 SSH 会话管理
├── sftp           — SFTP 文件传输服务
├── terminal       — JediTerm 集成，305+ 配色方案
├── ui             — JavaFX 视图，主题，i18n，连接导入
├── plugin-api     — 插件开发者公共 SPI（可独立发布的 JAR）
├── plugin-loader  — 插件发现、生命周期、per-session 能力总线
└── plugins
    ├── plugin-demo    — Script Snippets 示例（readConfig 能力）
    └── plugin-sysmon  — System Monitor 实时图表 + getMetrics 能力
```

## 技术栈

- **Java 21** + **JavaFX 21**（UI）
- **SSHJ**（SSH/SFTP 客户端）
- **JediTerm**（终端模拟器，Swing → SwingNode）
- **JDBI 3** + **HikariCP** + **SQLite**（持久化，WAL 模式）
- **Bouncy Castle**（AES-256-GCM 凭据加密）
- **Gson**（JSON / JSON-RPC 编解码）
- **OSHI**（系统指标，plugin-sysmon 使用）
- **jlink**（自包含 JRE，约 50 MB）

## 许可证

MIT
