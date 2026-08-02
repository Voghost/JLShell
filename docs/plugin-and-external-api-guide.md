# JLShell 插件与外部 API 开放指南

> 面向：需要扩展 JLShell 的开发者与 Agent。本文以仓库当前实现为准；先阅读本文，再按需查看 `plugins/plugin-session-demo`、`plugins/plugin-program-demo` 两个可运行示例。外部 JSON-RPC 宿主 SPI 的完整契约见 [`program-api/README.md`](../program-api/README.md)。

## 1. 先选扩展方式

| 目标 | 选择 | 生命周期与作用域 | 是否能直接使用 SSH |
| --- | --- | --- | --- |
| 为某一个终端/SSH 会话增加功能、标签页或远程操作 | **会话插件** `JlShellPlugin` | 每个工作区会话各自激活、各自注册能力 | SSH 会话中可以；本地终端中为空 |
| 为整个应用增加设置页、后台功能或无会话能力 | **程序插件** `JlShellProgramPlugin` | 应用启动后加载一次，能力为全局能力 | SDK 1.1.0 起可注册受控会话贡献 |
| 让本机其他程序/Agent 管理已保存连接、会话、命令或调用插件 | **外部 API**（HTTP JSON-RPC） | 仅本机 `127.0.0.1`，需 Bearer Token | 可通过 API 操作已有 SSH 会话 |

三者共用同一套能力总线。程序插件注册全局能力（`sessionId = null`）；会话插件注册的能力按 `(sessionId, pluginId, capability)` 隔离；外部 API 用 `capability.invoke` 调用它们。

## 2. 公共约定

- Java 21、Maven 3.9+；首个公开 SDK 版本为 `1.0.0`。Program 插件需要直接扩展
  SSH 会话时使用 `net.oomn.jlshell:plugin-api:1.1.0`；扩展宿主 JSON-RPC 方法时另用
  同版本的 `net.oomn.jlshell:program-api`。两者都必须使用 `provided` scope。
- 每个插件的 `id()` 必须是稳定且唯一的反向域名，例如 `com.example.deploy-tools`。它同时是能力路由键和私有存储命名空间，发布后不要随意修改。
- 必须提供名称、版本、描述和宿主兼容范围。兼容范围为空会在插件页显示警告；不在范围内会显示不兼容。
- 所有可能阻塞的 SSH、文件、网络或计算工作都必须异步执行，不能阻塞 JavaFX UI 线程。
- 宿主会在停用插件时清理其已注册能力。插件仍应在 `deactivate()` 中关闭自身线程、订阅、交互式会话和 UI 资源；手动 `unregister` 是可选的提前清理。
- 外部插件安装或替换 JAR 后重启 JLShell。插件扫描是惰性的，但已完成扫描的进程不会自动发现之后复制的新 JAR。

## 3. 会话插件

### 3.1 加载位置与 SPI

会话插件 JAR 从以下目录加载：

1. 用户目录：`~/.jlshell/plugins/`
2. 打包应用目录旁的 `plugins/`（用于内置/随包插件）

实现接口：

```java
public final class DeployToolsPlugin implements JlShellPlugin {
    @Override public String id() { return "com.example.deploy-tools"; }
    @Override public String displayName() { return "Deploy Tools"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String author() { return "Example"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
    @Override public String description() { return "Per-session deployment tools."; }
    @Override public boolean requiresSshSession() { return true; }

    @Override
    public void activate(PluginContext context) {
        // 注册能力、创建可选的工作区标签页、保存 context。
    }

    @Override
    public void deactivate() {
        // 关闭自身资源；不要再使用已失效的 context。
    }
}
```

在 `src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellPlugin` 写入实现类全限定名：

```text
com.example.deploy.DeployToolsPlugin
```

### 3.2 `PluginContext` 提供的能力

| API | 用途 | 约束 |
| --- | --- | --- |
| `sshSession()` | 获取 `SshSessionContext`，含会话 ID、主机信息及 SSH 能力 | 返回 `Optional`；本地终端或无 SSH 时为空 |
| `openTab` / `closeTab` / `updateTabTitle` | 管理本插件的工作区标签页 | 仅管理本插件打开的标签页 |
| `capabilityRegistry()` | 注册当前会话可被其他插件/API 调用的能力 | 每个会话独立；宿主自动绑定本插件 ID |
| `capabilityBus()` | 调用程序插件或同一会话中其他插件的能力 | 旧宿主可能返回 `null`，使用前检查 |
| `storage()` | 插件私有持久化 KV 存储 | 旧宿主可能返回 `null`；不能读写其他插件数据 |
| 主题、语言、`resolveI18n` | 读取主题/语言，并监听变更或取得宿主翻译 | 主题和语言 property 为只读 |
| 日志与通知 | `debug/info/warn/error`、`showNotification` | 日志可用；通知 UI 目前为宿主预留接口，勿将可靠业务流程依赖在通知回调上 |

`SshSessionContext` 的远程操作包括：

- `commandExecutor()`：一次性执行命令，返回 `CompletableFuture<CommandOutput>`；可指定超时。
- `interactiveCommandExecutor()`：启动需要反复读写 stdin/stdout 的交互命令，返回 `InteractiveSession`。
- `fileExplorer()`：列目录、读、写、删远程文件。
- `logViewer()`：读取末尾日志或 follow 日志。
- `serverStatus()`：CPU、内存、磁盘、进程状态。

所有以上操作均是异步 API。UI 更新必须切回 JavaFX 线程，例如 `Platform.runLater(...)`。

### 3.3 注册会话能力

能力名称仅在本插件内命名，例如 `readTextFile`；宿主会把插件 ID 自动加入路由，因此不要在能力名中重复插件 ID。

```java
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.rpc.Capability;

@Override
public void activate(PluginContext context) {
    context.capabilityRegistry().register(Capability.builder("readTextFile")
            .description("读取当前 SSH 会话中的 UTF-8 文本文件")
            .requiresSession(true)
            .handler((args, capabilityContext) -> {
                JsonObject input = args.getAsJsonObject();
                String path = input.get("path").getAsString();
                return capabilityContext.sshSession().orElseThrow()
                        .fileExplorer().readFile(path)
                        .thenApply(bytes -> {
                            JsonObject result = new JsonObject();
                            result.addProperty("path", path);
                            result.addProperty("content",
                                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            return result;
                        });
            })
            .build());
}
```

可选的 `inputSchema(JsonObject)` 只用于展示和轻量自省，宿主**不会**执行完整 JSON Schema 校验。能力处理器必须自行验证参数、超时和权限，并将失败以异常 future 返回。

会话关闭或插件停用后，能力自动消失。通过界面或插件总线调用时，目标插件需要已在目标会话激活；通过外部 API 的 `capability.invoke` 调用时，若首次找不到会话能力，宿主会在后台无界面激活目标会话插件并自动重试，无需用户手动打开插件标签页。

## 4. 程序插件

### 4.1 加载位置与 SPI

程序插件在 API Server 启动前加载一次，JAR 目录为：

1. 用户目录：`~/.jlshell/program-plugins/`
2. 打包应用目录旁的 `program-plugins/`

实现 `JlShellProgramPlugin`，并在下列 ServiceLoader 文件登记实现类：

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin
```

最小骨架：

```java
public final class AgentBridgePlugin implements JlShellProgramPlugin {
    @Override public String id() { return "com.example.agent-bridge"; }
    @Override public String displayName() { return "Agent Bridge"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String description() { return "Global agent integration."; }

    @Override
    public void activate(ProgramPluginContext context) {
        context.capabilityRegistry().register(Capability.builder("health")
                .description("返回桥接插件健康状态")
                .requiresSession(false)
                .handler((args, ignored) -> CompletableFuture.completedFuture(new JsonObject()))
                .build());
    }

    @Override public void deactivate() {}
}
```

程序插件的 `ProgramPluginContext` 提供全局 `capabilityRegistry()`、`capabilityBus()`、私有 `storage()`、主题/语言/i18n、日志和通知。Program 插件本体没有固定 SSH 上下文；SDK 1.1.0 起可以通过 `sessionIntegration()` 注册一个会话贡献，由宿主在用户打开该贡献时提供生命周期受控的 `PluginContext` 和 `SshSessionContext`。

程序插件可覆写 `settingsView(ProgramPluginContext)` 返回 JavaFX `Node`，其设置页会展示在“偏好设置 → Plugins”。不要在该方法中执行阻塞操作。

### 4.2 单一 Program 插件扩展 SSH 会话（SDK 1.1.0）

适合同时需要全局后台进程、账号安全存储和 SSH 会话操作的产品插件。用户只安装一个
Program 插件 JAR；不需要第二个 `JlShellPlugin`、第二个插件 ID 或 Session ServiceLoader。

```java
private Registration sessionRegistration;

@Override
public void activate(ProgramPluginContext context) {
    sessionRegistration = context.sessionIntegration().register(new ProgramSessionContribution() {
        @Override public String displayName() { return "Agent Bridge"; }
        @Override public String description() { return "Deploy and connect the remote Agent."; }

        @Override
        public ProgramSessionController activate(PluginContext sessionContext) {
            SshSessionContext ssh = sessionContext.sshSession().orElseThrow();
            sessionContext.openTab("Agent Bridge", buildView(ssh));
            return () -> stopSessionTasks(ssh.sessionId());
        }
    });
}

@Override
public void deactivate() {
    if (sessionRegistration != null) sessionRegistration.close();
}
```

宿主保证以下行为：

- 每个 Program 插件最多注册一个会话贡献，插件身份、安装、启停和订阅策略仍只有一份。
- 同一贡献可同时在多个 SSH 会话中激活，每个会话获得独立上下文和控制器。
- 会话断开、重连、Program 插件停用/升级或应用退出时，控制器、标签页和会话能力都会回收。
- 插件只能使用 `SshSessionContext` 门面，不能获得底层 SSHJ 对象；失效上下文不得缓存或继续调用。

## 5. 插件间能力调用

插件间和外部 API 的请求信封一致：

```java
RpcRequest request = new RpcRequest(
        sessionId,                           // 全局能力为 null
        "com.example.deploy-tools",          // 目标插件 ID
        "readTextFile",                      // 目标能力名
        args,                                 // Gson JsonElement；无参数可为 JsonNull
        "request-123"                        // 可选、由调用方追踪
);

context.capabilityBus().invoke(request).thenAccept(response -> {
    if (response.error() != null) {
        // response.error().code()/message()
        return;
    }
    // response.result()
});
```

路由规则：

- `sessionId = null`：仅查找程序插件注册的全局能力。
- 非空 `sessionId`：仅查找该会话中激活的会话插件能力。
- 缺少插件、能力或会话时返回 `-32601`；能力处理异常返回 `-32603`。
- 不能跨会话隐式访问能力；必须显式提供目标 `sessionId`。

## 6. 外部 API（本机 HTTP JSON-RPC 2.0）

### 6.1 启用与鉴权

在 **偏好设置 → API** 启用服务并设置端口；保存后需要重启应用。端口填 `0` 时由系统选择空闲端口。服务始终只监听：

```text
http://127.0.0.1:<实际端口>/rpc
```

默认关闭。启用时 Token 保存在 `~/.jlshell/api.token`；POSIX 系统会以 `0600` 创建。每个请求必须包含：

```text
Authorization: Bearer <api.token 的内容>
Content-Type: application/json
```

请把 Token 视为本机高权限凭据：持有它的本机进程能连接已保存的连接配置、执行远程命令，并调用暴露的插件能力。不要上传 Token、不要将本机端口代理或映射到公网，也不要在日志中打印它。`api.token` 方法会返回 Token，正常自动化不应调用它；只应从受保护的本地文件或设置页读取。

### 6.2 请求与响应格式

请求为标准 JSON-RPC 2.0：

```json
{
  "jsonrpc": "2.0",
  "id": "request-1",
  "method": "session.list",
  "params": {}
}
```

成功响应：

```json
{"jsonrpc":"2.0","id":"request-1","result":[]}
```

失败响应：

```json
{"jsonrpc":"2.0","id":"request-1","error":{"code":-32602,"message":"..."}}
```

HTTP 层的 `401` 表示 Token 错误，`405` 表示非 POST。其他协议和业务错误通常仍以 HTTP `200` 返回，并放在 JSON-RPC `error` 中。常见错误码为：`-32700` JSON 解析错误、`-32600` 非法请求、`-32601` 方法/能力不存在、`-32602` 参数错误、`-32603` 内部或能力执行错误。

通用调用示例：

```bash
TOKEN="$(cat ~/.jlshell/api.token)"
curl --fail-with-body \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"session.list","params":{}}' \
  http://127.0.0.1:<port>/rpc
```

### 6.3 内置方法

| 方法 | 参数 | 返回值 |
| --- | --- | --- |
| `session.connect` | `connectionId`（已保存连接的 ID） | `{ "sessionId": "..." }` |
| `session.disconnect` | `sessionId` | `null` |
| `session.list` | 无 | `[{sessionId, displayName, host, user, state}]` |
| `session.info` | `sessionId` | `{sessionId, displayName, host, port, user}` |
| `command.run` | `sessionId`、`command`、可选 `timeoutSec`（默认 30） | `{stdout, stderr, exitCode}` |
| `capability.list` | 可选 `sessionId` | 当前作用域的能力规格数组 |
| `capability.invoke` | `pluginId`、`capability`、可选 `sessionId`、`args`、`requestId` | 插件返回的 JSON 值；会话能力首次调用会后台无界面激活目标插件后重试 |
| `api.methods` | 无 | 内置方法名称数组 |
| `api.token` | 无 | 当前 Token（不建议用于自动化） |

典型 Agent 流程：先 `session.connect`（使用应用中已保存的 `connectionId`），取得 `sessionId`；再调用 `command.run` 或 `capability.invoke`。对已安装的会话插件，首次 `capability.invoke` 会无界面激活插件并重试该请求；最后调用 `session.disconnect`，它会同步清理该会话的插件。不要在请求中传递密码、私钥或未保存连接参数——当前 API 不支持，也不应绕过 JLShell 的凭据保护。

调用程序插件的全局能力：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "capability.invoke",
  "params": {
    "pluginId": "com.jlshell.demo.program-host-tools",
    "capability": "hostInfo",
    "args": {}
  }
}
```

调用会话插件能力：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "capability.invoke",
  "params": {
    "sessionId": "<session-id>",
    "pluginId": "com.jlshell.demo.session-tools",
    "capability": "readTextFile",
    "args": {"path": "/etc/hosts"}
  }
}
```

### 6.4 扩展外部方法（Program API SPI）

除 `capability.invoke` 外，程序插件还可通过 `ProgramApiProvider` 直接注册新的 JSON-RPC 方法。插件实现类需同时实现 `JlShellProgramPlugin` 与 `ProgramApiProvider`，并继续只在 `JlShellProgramPlugin` 的 ServiceLoader 文件中登记。加载器会在程序插件激活后注册 API provider，随后才创建 API Server。

`plugin-program-demo` 提供两个可调用方法：

| 方法 | 行为 |
| --- | --- |
| `demo.host.info` | 返回 JLShell 运行环境、主题和语言信息 |
| `demo.echo` | 原样返回请求的 `params` |

例如：

```json
{"jsonrpc":"2.0","id":4,"method":"demo.echo","params":{"message":"hello"}}
```

自定义方法必须使用插件专属命名空间，且不得覆盖系统方法。注册表检测到重复方法名会拒绝启动该插件。插件包必须将 `program-api`（以及 `plugin-api`）标记为宿主提供依赖，不能 shade 进 JAR；完整接口、会话抽象、打包配置和安全要求见 [`program-api/README.md`](../program-api/README.md)。

### 6.5 当前自省限制

`capability.list` 会返回 `name`、`description`、`requiresSession` 和可选 `inputSchema`，**当前实现不返回 `pluginId`**。因此纯外部 Agent 不能仅凭此方法的结果拼出可调用的完整路由；应通过插件文档、固定配置，或 JLShell“偏好设置 → API”中的能力浏览器取得 `pluginId/capability` 对。为每个对外能力维护稳定的插件 ID 和名称。

## 7. 构建、打包与安装

可直接以现有 demos 为模板。一个插件项目至少应：

1. 依赖 `net.oomn.jlshell:plugin-api`；使用 JavaFX UI 时也依赖 `javafx-controls`。
2. 生成含自身第三方依赖的 fat JAR，但不要把 `plugin-api`、JavaFX 或 SLF4J 打进 JAR（否则会造成宿主类加载冲突）。仓库 `plugins/pom.xml` 已提供 Shade 配置示例。
3. 在 `META-INF/services/` 中登记正确 SPI。
4. 将会话插件 JAR 放到 `~/.jlshell/plugins/`，程序插件 JAR 放到 `~/.jlshell/program-plugins/`，然后重启。

构建仓库示例插件：

```bash
mvn -f plugins/pom.xml clean package
./plugins/build-and-install.sh install
```

推荐先复制并修改下列参考实现，而不是从零猜测宿主行为：

- 会话插件与 SSH/能力示例：`plugins/plugin-session-demo`
- 程序插件与全局能力/设置页示例：`plugins/plugin-program-demo`
- 可调用 API 的完整列表：应用中的“偏好设置 → API”，或 `api.methods`

## 8. Agent 执行清单

在让 Agent 访问 JLShell 前，提供以下最小上下文：

1. API 是否已启用、实际端口，以及以安全方式注入的 Token（不要写入提示词或持久日志）。
2. 可使用的 `connectionId` 白名单，以及允许执行的命令/文件路径范围。
3. 已安装插件的稳定 `pluginId`、能力名、参数 schema、需要的 `sessionId` 类型。
4. 操作完成后是否必须断开会话；默认建议断开由 Agent 新建的会话。
5. 任何远程写入、删除或执行高风险命令，都必须在 Agent 策略中明确授权，不能因 API 可调用而自动视为已获授权。
