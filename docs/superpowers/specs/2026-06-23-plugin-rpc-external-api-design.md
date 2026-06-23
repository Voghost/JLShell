# Plugin RPC + External API 设计

> **For agentic workers:** 本 spec 经 brainstorming 产出，下一步用 superpowers:writing-plans 生成逐任务实现计划。

**Goal:** 为插件新增 per-session 能力注册表，使插件之间可跨插件调用 RPC（"某 session 的某 plugin 取信息，传给另一个 plugin"）；并新增一个绑定 localhost 的轻量 HTTP JSON-RPC server，供外部调用方（及后续 AI MCP）通过同一内核新建连接、在终端跑命令、透传并调用插件能力。

**Architecture:** 内核契约（`Capability` / `CapabilityRegistry` / `RpcRequest` / `RpcResponse` / `CapabilityBus`）落在 `plugin-api` 的一个无 JavaFX 依赖的新包；per-session 路由实现落在 `plugin-loader`；HTTP JSON-RPC 传输落在全新 `api-server` 模块（JDK `com.sun.net.httpserver`，零新外部依赖）；`app` 负责接线 + host method 实现 + 偏好设置开关 + token 文件；`ui` 加偏好设置 API Tab；demo 插件加一个示例能力证明闭环。外部 API 与插件间 RPC **共享同一 `CapabilityBus` 内核**，因此"透传 plugin 通信消息"是同一总线的另一传输端，天然实现。

**Tech Stack:** Java 21、JDK `com.sun.net.httpserver`（HTTP server）、Gson 2.11.0（已在 parent dependencyManagement）、JSON-RPC 2.0。

---

## 兼容性原则（硬约束，贯穿全部改动）

1. **只增不改不删**：对 `JlShellPlugin` / `PluginContext` / `SshSessionContext` / `PluginView` 及现有 capability 接口的所有改动，都是添加 `default` 方法或新增类型。绝不改/删现有方法签名。
2. **能力注册纯 opt-in**：旧插件不实现任何新方法、不调 `capabilityRegistry()`，其现有 UI / SSH / tab 功能完全照旧。
3. **`PluginManager` 的 per-session 改造是内部行为**：对外仍提供 `activatePlugin(String id, PluginContext ctx)` 等现有方法；只有 host 代码（`PluginsTabView` / `TerminalWorkspaceView` / `AppContext`）同步更新。
4. **`api-server` 是全新模块**：旧插件根本不依赖它，无关。SPI 注册文件（`META-INF/services`）不变。
5. **每个新接口的 default/空实现都要 no-op 安全**：旧插件万一触碰到不会 NPE 或抛异常。`PluginContext.capabilityRegistry()` 的 default 返回 `CapabilityRegistry.empty()`（注册即丢弃、resolve 永远空），而非 null。

**兼容回归测试项（写进实现计划）：**
- 旧版 `plugin-sysmon` / `plugin-demo` fat jar 在新 host 下加载、开 tab、主题/语言切换、SSH 命令执行全部照旧。
- 同一插件在两个 SSH 会话同时激活，各自独立、互不覆盖、各自停用不影响对方（新行为，顺带修旧 bug）。

---

## Part 1 — 架构与内核布局

### 模块布局

```
plugin-api    +--- new package com.jlshell.plugin.api.rpc (无 JavaFX 依赖):
              |      Capability, CapabilitySpec, CapabilityHandler, CapabilityContext,
              |      CapabilityRegistry, RpcRequest, RpcResponse, RpcError, CapabilityBus
              |    PluginContext 新增 default capabilityRegistry()（capability 包，非 rpc 包，
              |    可引用 JavaFX，但 empty() 实现位于 rpc 包）
plugin-loader +--- 实现 per-session 路由/分发：
              |      PluginManager 内部 activePlugins 由全局单 map 改为 per-session 桶
              |      CapabilityBusImpl、CapabilityRegistryImpl、DefaultPluginContext 持有 registry
api-server    (NEW)--- com.sun.net.httpserver、JSON-RPC 2.0 codec、bearer token、
              |      内置 host method handler、MCP 接入点（本次不实现）
app           +--- AppContext 接线、HostMethodsImpl、ApiTokenStore、shutdown stop()
              |    MainWindow 收 apiServer 引用传给偏好设置
ui            +--- PreferencesDialog 新增 "API" Tab + i18n keys
plugins/plugin-demo +--- 注册一个示例能力 readConfig（证明闭环）
```

### 模块依赖（零新外部库）

- `api-server` → `core` + `plugin-api`（仅 rpc 包契约）+ `gson`（显式声明，版本由 parent 管）。**不依赖 ui/JavaFX**。
- 因 `plugin-api` 带传递性 `javafx-controls`，`api-server` 的 pom 用 `<exclusions>` 去掉 `javafx-controls`，确保 api-server 仅用无 JavaFX 的 rpc 包契约。这是实现计划里的一个明确项。
- `app/pom.xml` 新增 `<dependency>api-server</dependency>`。
- **打包修复**：`build-dist.sh` 的 `detect_modules()` 加 `jdk.httpserver`（否则 jlink JRE 缺该模块，打包版启用 API server 即 `NoClassDefFoundError`）。

### 内核信封（内部与外部共享 → 真透传）

```java
// com.jlshell.plugin.api.rpc
public record RpcRequest(String sessionId, String pluginId, String capability,
                         JsonElement args, String requestId) {}
public record RpcResponse(JsonElement result, RpcError error) {}
public record RpcError(int code, String message, JsonElement data) {}

public interface CapabilityBus {
    CompletableFuture<RpcResponse> invoke(RpcRequest request);   // 异步
    List<CapabilitySpec> listCapabilities(String sessionId);     // null = 仅全局
}
```

外部 HTTP server 把 JSON-RPC 帧映射成 `RpcRequest` 调 `CapabilityBus.invoke()`，插件间调用也走同一 `invoke()`——**同一总线，透传零额外成本**。

---

## Part 2 — 能力注册表与声明

### 新类型（全在 `com.jlshell.plugin.api.rpc`）

```java
@FunctionalInterface
public interface CapabilityHandler {
    CompletableFuture<JsonElement> invoke(JsonElement args, CapabilityContext ctx) throws Exception;
}

public interface CapabilityContext {
    String sessionId();                        // null = 全局能力
    Optional<SshSessionContext> sshSession();  // session 作用域能力才有
    PluginContext pluginContext();             // 拿回原 context（theme/i18n/log 等）
}

public record CapabilitySpec(
    String name,            // 如 "readConfig"（不含 pluginId，路由时拼）
    String description,
    JsonObject inputSchema, // JSON Schema，null = 无参
    boolean requiresSession
) {}

public interface Capability {
    String pluginId();            // host 注入，插件不用填
    CapabilitySpec spec();
    CapabilityHandler handler();
    static Builder builder(String name) { return new Builder(name); }
    // Builder 方法: description(String) / inputSchema(JsonObject) / requiresSession(boolean) / handler(CapabilityHandler)
}

public interface CapabilityRegistry {
    void register(Capability capability);
    void unregister(String name);
    List<CapabilitySpec> specs();
    Optional<Capability> resolve(String name);
    static CapabilityRegistry empty() { /* no-op: register 丢弃、resolve 返回 empty */ }
}
```

### `PluginContext` 唯一新增（default 方法）

```java
default CapabilityRegistry capabilityRegistry() {
    return CapabilityRegistry.empty();   // 旧插件不调无影响；调了也安全
}
```

### 插件声明能力（运行时 register，在 `activate()` 里）

```java
@Override
public void activate(PluginContext ctx) {
    // ... 现有 openTab 逻辑保持不变 ...
    ctx.capabilityRegistry().register(
        Capability.builder("readConfig")
            .description("读取远程配置文件并返回内容")
            .inputSchema(JSON_SCHEMA)        // JsonObject
            .requiresSession(true)
            .handler((args, capCtx) -> {
                String path = args.getAsJsonObject().get("path").getAsString();
                return capCtx.sshSession().orElseThrow().fileExplorer()
                    .readFile(path)
                    .thenApply(bytes -> gson.toJsonTree(new String(bytes, UTF_8)));
            })
            .build());
}
```

### 生命周期与清理

- host 的 per-session registry 在 `plugin.deactivate()` 时**自动移除该 pluginId 的全部能力**——插件不必手动 unregister。
- 插件也可显式 `unregister(name)` 做提前清理。

### JSON Schema 用途

- 初版 host 只做"参数是否存在"的轻量校验 + 把 schema 透出给 `listCapabilities()`（供外部自省和未来 MCP tool 清单自动生成）。
- **不引入完整 JSON Schema 校验引擎**——保持轻量。schema 是 `JsonObject`，用 Gson 手写或插件自带。

---

## Part 3 — per-session 路由与 `PluginManager` 改造

### 现状问题

`PluginManager.activePlugins` 是单一 `Map<pluginId, plugin>`。会话 A 和会话 B 都激活 "com.x" → 后者覆盖前者，`deactivate`/主题通知打到错误实例。这是既有 bug，per-session 路由修正它，且对外行为对旧插件不变（旧插件从不自己调 activate/deactivate，只有 host 调）。

### 改造（内部结构变，公共方法只增不删）

```java
// plugin-loader: PluginManager 内部
// 旧: Map<String pluginId, JlShellPlugin> activePlugins;
// 新: per-session 桶
private final Map<String sessionId, SessionPluginSet> activeBySession = new ConcurrentHashMap<>();
// SessionPluginSet = { Map<pluginId, JlShellPlugin>, CapabilityRegistry registry, String sessionId }
```

### 公共方法（只增不删，旧签名保留并语义升级）

| 方法 | 状态 | 行为 |
|---|---|---|
| `activatePlugin(String id, PluginContext ctx)` | **保留**（签名不变） | 按 ctx 所属 sessionId 入对应桶；同一桶内重复激活同插件幂等（防 `PluginsTabView` 和 `TerminalWorkspaceView` 两个入口双激活） |
| `deactivatePlugin(String sessionId, String pluginId)` | **新增** | per-session 精确停用，清理该插件能力 |
| `deactivatePlugin(String pluginId)` | **保留**（旧签名） | legacy：跨所有 session 停用该 pluginId（应用退出/安全网） |
| `deactivateAll()` | 保留 | 清所有桶 |

### sessionId 来源（关键兼容点）

- **SSH 会话**：`ctx.sshSession().get().sessionId()`——`SshSessionContextAdapter` 已暴露 `sessionId()`。
- **本地 shell tab**：`SshSessionContext` 为空。`MainWindow` 在建本地 tab 时生成合成 key `"local-" + uuid`，传入该 tab 的 `PluginsTabView` / `TerminalWorkspaceView`，作为该 tab 的 sessionId。两个 host 调用点改为传这个 key。

### 两个 host 调用点同步更新（不改旧插件，只改 host 代码）

- `PluginsTabView` / `TerminalWorkspaceView`：构造时拿到 `sessionId`（SSH 用 `sshSession.sessionId().toString()`，本地 shell 用传入的合成 key），`stopPlugins()` 改调 `pluginManager.deactivatePlugin(sessionId, pluginId)`。
- 这两处现在已有 `activatedPluginIds` 集合 + `sshSession` 引用，改动很小。

### `DefaultPluginContext` 增字段（不影响旧插件）

```java
public DefaultPluginContext(String pluginId, String sessionId,
                            CapabilityRegistry registry,   // 该 session 的 per-session registry
                            Optional<SshSessionContext> sshSession, Callbacks callbacks) {...}
@Override public CapabilityRegistry capabilityRegistry() { return registry; }  // 非空，no-op-safe
```

- 新增构造参数有默认值重载；`PluginsTabView`/`TerminalWorkspaceView` 用新构造。
- 保留一个兼容旧签名的构造委托到空 registry（防直接 new 的旧测试断裂）。

### `CapabilityBus` 实现（`plugin-loader`，依赖 per-session map）

```java
public CompletableFuture<RpcResponse> invoke(RpcRequest req) {
    // 1. req.sessionId == null → 查 global registry（无 session 能力）
    // 2. 否则查 activeBySession[sessionId].registry → resolve(pluginId + "." + capability)
    //    实际 resolve 按能力名(name)，pluginId 用来定位是哪个插件注册的（同 session 可能多插件）
    // 3. 找不到 → RpcResponse(null, RpcError(-32601, "method not found"))
    // 4. 找到 → handler.invoke(args, ctx) → 成功包 result / 异常包 error（不抛过 HTTP 边界）
}
```

### 路由键精确语义

- 能力按 `(sessionId, pluginId, capabilityName)` 三元组定位。
- `resolve` 在某 session 的 registry 里按 `pluginId + name` 查（注册时 host 给 `Capability` 打上 `pluginId()`）。
- `listCapabilities(sessionId)` 返回该 session 全部插件的能力清单拼上 pluginId——供 MCP/外部自省。

---

## Part 4 — `api-server` 模块（HTTP JSON-RPC + token + 内置 method）

### Server 组件

```java
// com.jlshell.api.server
public final class ApiServer {
    public ApiServer(Config cfg, CapabilityBus bus, HostMethods host, Gson gson);
    public void start();   // 启动 HttpServer，绑 127.0.0.1
    public void stop();
    public int port();     // 实际监听端口（cfg.port=0 时自动选空闲口）
    public String token(); // 供 UI 展示
    public record Config(int port, String token, boolean enabled) {}
}
```

- HTTP server = JDK `com.sun.net.httpserver.HttpServer`。
- 单端点 `/rpc`，POST + `Authorization: Bearer <token>`，Content-Type `application/json`。
- JSON-RPC 2.0 codec：`{jsonrpc,id,method,params}` ↔ `RpcRequest`，响应 `{jsonrpc,id,result}` 或 `{jsonrpc,id,error:{code,message,data}}`。
- 错误码用 JSON-RPC 标准：`-32600` 非法请求、`-32601` 方法未找到、`-32602` 参数无效、`-32603` 内部错误、`-32000` host 自定义。
- 单条请求一个 handler 线程（`HttpServer` 默认线程池）；capability handler 本身异步 `CompletableFuture`，server 等它完成再回写。

### 两类 method，统一走同一总线

**1. 透传插件能力（直接复用 `CapabilityBus`）：**

```jsonc
{"method":"capability.invoke",
 "params":{"sessionId":"uuid","pluginId":"com.x","capability":"readConfig","args":{"path":"/etc/hosts"}}}
// → bus.invoke(RpcRequest(sessionId, pluginId, capability, args)) → 透传 RpcResponse

{"method":"capability.list","params":{"sessionId":"uuid"}}  // → List<CapabilitySpec>（含 pluginId）
```

**2. 内置 host method（`HostMethods` 接口在 `api-server` 定义，`app` 实现）：** 不经 capability bus，直接调 core 服务。

| method | params | result | 实现 |
|---|---|---|---|
| `session.connect` | `{connectionId}` | `{sessionId}` | `ConnectionProfileService.toConnectionRequest` + `SessionManager.openSession` |
| `session.disconnect` | `{sessionId}` | ok | `SessionManager.closeSession` |
| `session.list` | `{}` | `[{sessionId,host,user,state}...]` | `SessionManager.listSessions` |
| `session.info` | `{sessionId}` | `{host,port,user,...}` | `sessionRegistry` |
| `command.run` | `{sessionId, command, timeoutSec?}` | `{stdout, stderr, exitCode}` | `SshSession.execute(CommandRequest(command, Duration.ofSeconds(timeoutSec or 30), false, null))` |
| `api.token` | `{}` | `{token}` | 已通过 bearer 鉴权后返回当前 token（供调用方确认/自取） |
| `api.methods` | `{}` | `{methods:[{name, paramsSchema, resultSchema?}, ...]}` | 自省，供 MCP/外部发现；内置 host method + 透传 capability.invoke/list 的描述 |

- `session.connect` 复用现有 `ConnectionProfileService.toConnectionRequest(connectionId)` + `SessionManager.openSession()`——**纯后台建连，不开 tab**（headless 友好，适合 MCP）。如需开 tab 后续加 `openWorkspace:true` 选项。
- host method 实现在 `app` 侧（持有 `ConnectionProfileService`/`SessionManager`/`SshSession`/executor）。`api-server` 只定义 `HostMethods` 接口 + JSON-RPC 分发。

### MCP 预留（本次不实现，只留接入点）

- `api-server` 内部把 method 调度抽象成 `MethodDispatcher`（method 名 → handler），`/rpc` 端点和（未来）`/mcp` 端点共用同一 dispatcher。
- 计划里留一个**未实现**的 `McpEndpoint` 占位 + TODO 注释，说明 Streamable HTTP / stdio 适配器如何复用 `MethodDispatcher` + `capability.list` 生成 MCP tools 清单。本次只交付 `/rpc`。

### Token 与开关

- Token 文件 `~/.jlshell/api.token`，内容 = 32 随机字节 base64，文件权限 `600`。首次启用时生成、存盘。
- 偏好设置开关：`api.enabled`（默认 false）、`api.port`（默认 0=自动）。改后**重启生效**（与"语言改需重启"一致）。
- `app_settings` 存 `api.enabled` / `api.port`（`AppSettingsService` key-value，与 `ui.language` 同机制）。

---

## Part 5 — 接线 + 偏好设置 API Tab + 错误处理 + 测试

### `AppContext` 接线（新增，插在 plugin 之后、UI 之前）

```java
// 6b. RPC 内核 + 外部 API（在 pluginManager 之后）
CapabilityBusImpl capabilityBus = new CapabilityBusImpl(pluginManager);
HostMethodsImpl hostMethods = new HostMethodsImpl(
        connectionProfileService, sessionManager, sessionRegistry, executor);

boolean apiEnabled = "true".equalsIgnoreCase(appSettingsService.get("api.enabled", "false"));
int apiPort = parseOrDefault(appSettingsService.get("api.port", "0"), 0);
String apiToken = ApiTokenStore.loadOrCreate();   // ~/.jlshell/api.token, 600

ApiServer.Config apiCfg = new ApiServer.Config(apiPort, apiToken, apiEnabled);
ApiServer apiServer = new ApiServer(apiCfg, capabilityBus, hostMethods, gson);
if (apiEnabled) {
    try {
        apiServer.start();
        log.info("External API on 127.0.0.1:{} (token at ~/.jlshell/api.token)", apiServer.port());
    } catch (IOException e) {
        log.warn("API server failed to start (non-fatal): {}", e.getMessage());
    }
}
// MainWindow 多传 apiServer
```

- `CapabilityBusImpl` 由 `pluginManager` 构造（plugin-loader 内部，它持有 per-session map），`app` 只 new 一个壳。
- `HostMethodsImpl` 在 `app`（持有 `ConnectionProfileService`/`SessionManager`/executor）。
- shutdown hook 加 `apiServer.stop()`。
- **server 启动失败（端口占用）不阻断应用启动**——API 是可选增强，log + 通知即可。端口 0 自动选可避开占用。

### 偏好设置新增 "API" Tab（套现有 import Tab 模式）

- `PreferencesDialog.show()` 签名加 `ApiServer apiServer` 参数（唯一 call site `MainWindow` 同步传）。`show()` 里新加 `pendingApiEnabled[0]` / `pendingApiPort[0]`。
- `buildTabPane` 加 `apiTab`；`buildApiPane`：
  - CheckBox `api.enabled`（默认 off）
  - 端口输入框（占位 "0 = 自动"），仅 enabled 时可编辑
  - 只读 Label 显示 "当前:127.0.0.1:PORT, token 见 ~/.jlshell/api.token" + "复制 token" 按钮（`apiServer.token()`）+ "重启生效"提示
- `applyPendingSettings` 加 `appSettings.set("api.enabled", ...)` / `set("api.port", ...)`；若从 off→on 或端口变 → **触发 `showRestartPrompt`**（复用现有重启逻辑）。

### 错误处理（贯穿三层，统一不抛过边界）

- **插件 handler 抛异常** → `CapabilityBus.invoke` catch → `RpcResponse(null, RpcError(-32603, message, null))`。不向上抛。
- **路由找不到**（sessionId 不存在 / pluginId 未激活 / capability 未注册）→ `RpcError(-32601, "...")`。
- **参数缺失/类型不对** → host method 层校验 → `RpcError(-32602, "missing param: sessionId")`。
- **token 错/缺** → HTTP 层直接 401，不进 JSON-RPC。
- **非 POST / 非 JSON** → HTTP 400。
- **server 启动失败** → `AppContext` catch + log，不阻断 app 启动。
- 所有 `RpcError.message` 用英文短描述（机器消费），不 i18n。

### 测试策略（无 JavaFX 依赖的纯内核优先，UI 仅手测）

1. **`plugin-loader` 单测**（脱离 JavaFX）：
   - `CapabilityBusImplTest`：注册能力 → invoke 成功返回 JSON；未注册 → -32601；handler 抛异常 → -32603；全局 vs session 路由；两 session 同插件隔离。
   - `CapabilityRegistryTest`：register/unregister、specs 清单、deactivate 自动清理该 pluginId 能力。
   - per-session 隔离回归：同 pluginId 在两 session 激活，各自独立（顺带修旧 bug 的测试）。
2. **`api-server` 单测**（纯 JDK HttpServer + Gson，无 JavaFX）：
   - `JsonRpcCodecTest`：请求/响应编解码、错误码。
   - `ApiServerTest`：起 server（端口 0），用 `HttpClient` POST `/rpc`：正确 token → 200 + result；错 token → 401；`capability.list` 返回清单；`api.methods` 自省；`session.list` host method；`capability.invoke` 透传一个 mock 能力。
   - `HostMethodsImplTest`（app，mock `SessionManager`）：`session.connect` 走 mock。
3. **集成手测（验证清单）：**
   - 旧版 plugin-sysmon/plugin-demo fat jar 在新 host 下加载、开 tab、主题/语言切换照旧（兼容回归）。
   - demo 插件注册 `readConfig` 能力 → `curl -H "Authorization: Bearer <token>"` 调 `capability.invoke` 拿到文件内容。
   - 偏好设置开 API → 重启 → `curl` 能连；关 → 重启 → 连不上。
   - `session.connect` + `command.run` 跑通远程命令。
4. **打包回归：** `./build-dist.sh --mac` 出包后启用 API 不报 `NoClassDefFoundError`（验证 `jdk.httpserver` 已进 jlink）。

---

## Part 6 — 改动清单（文件级）

### 新增

- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/Capability.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilitySpec.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityHandler.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityContext.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityRegistry.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcRequest.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcResponse.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcError.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityBus.java`
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/DefaultCapability.java`（Builder impl）
- `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/EmptyCapabilityRegistry.java`（`empty()` impl）
- `plugin-loader/.../CapabilityBusImpl.java`
- `plugin-loader/.../CapabilityRegistryImpl.java`
- `plugin-loader/.../SessionPluginSet.java`
- `plugin-loader/.../CapabilityContextImpl.java`
- `api-server/pom.xml`
- `api-server/.../ApiServer.java`
- `api-server/.../ApiServerConfig.java`
- `api-server/.../ApiTokenStore.java`
- `api-server/.../jsonrpc/JsonRpcRequest.java`
- `api-server/.../jsonrpc/JsonRpcResponse.java`
- `api-server/.../jsonrpc/JsonRpcError.java`
- `api-server/.../jsonrpc/JsonRpcCodec.java`
- `api-server/.../jsonrpc/RpcHandler.java`（`/rpc` HttpHandler）
- `api-server/.../dispatch/MethodDispatcher.java`
- `api-server/.../dispatch/HostMethods.java`（接口）
- `api-server/.../dispatch/CapabilityInvokeMethod.java` / `CapabilityListMethod.java`
- `api-server/.../McpEndpoint.java`（占位 + TODO，不实现）
- `app/.../api/HostMethodsImpl.java`
- 各模块对应单测。

### 修改

- `plugin-api/.../PluginContext.java`：新增 `default CapabilityRegistry capabilityRegistry()`。
- `plugin-loader/.../PluginManager.java`：`activePlugins` → per-session `activeBySession`；新增 `deactivatePlugin(String, String)`；保留旧签名。
- `plugin-loader/.../DefaultPluginContext.java`：新增 `sessionId` + `registry` 字段与构造；实现 `capabilityRegistry()`。
- `ui/.../view/PluginsTabView.java`：拿到 sessionId，`stopPlugins()` 改 per-session 停用，用新构造。
- `ui/.../view/TerminalWorkspaceView.java`：同上（快速启动入口）。
- `ui/.../view/MainWindow.java`：本地 shell tab 生成合成 sessionId 传入；收 `apiServer` 传给偏好设置。
- `ui/.../dialog/PreferencesDialog.java`：`show()` 加 `ApiServer` 参数；新增 API Tab + `pendingApi` 状态；`applyPendingSettings` 加 API 设置 + 重启提示。
- `app/.../AppContext.java`：接线 `CapabilityBusImpl`/`HostMethodsImpl`/`ApiServer`；shutdown stop。
- `app/pom.xml`：加 `api-server` 依赖。
- `pom.xml`：`<modules>` 加 `api-server`。
- `build-dist.sh`：`detect_modules()` 加 `jdk.httpserver`。
- `plugins/plugin-demo/.../ScriptSnippetsPlugin.java`：`activate()` 里注册 `readConfig` 示例能力。
- `ui/src/main/resources/i18n/messages.properties` + `messages_zh_CN.properties`：新增 i18n keys。

### i18n keys（两个文件都加）

```
preferences.tab.api=API / API
api.enabled=Enable external API / 启用外部 API
api.port=Port / 端口
api.port.hint=0 = auto / 0 = 自动
api.current=Current: 127.0.0.1:{0} / 当前：127.0.0.1:{0}
api.tokenHint=Token stored at ~/.jlshell/api.token / Token 存于 ~/.jlshell/api.token
api.copyToken=Copy Token / 复制 Token
api.restartRequired=API settings require restart to take effect. / API 设置需重启后生效。
```

---

## 风险与边界

- **`plugin-api` 传递性 JavaFX**：`api-server` 必须用 `<exclusions>` 去掉 `javafx-controls`，且只引用 `com.jlshell.plugin.api.rpc` 包。若 Gson 解析 `RpcRequest` 时类加载链意外触发 JavaFX，需把 RPC 契约拆到更独立的位置——实现时验证。
- **`jdk.httpserver` 未进 jlink**：打包版启用 API 即崩。已列为明确任务。
- **MCP 本次不实现**：只留 `MethodDispatcher` + `McpEndpoint` 占位。真正的 MCP server（Streamable HTTP / stdio）是后续独立 spec。
- **端口占用**：用户指定端口可能冲突；端口 0 自动选避开；占用时 log + 不阻断 app。
- **连接不开 tab**：`session.connect` 纯后台建连。需 UI tab 的话后续加 `openWorkspace` 选项（本次不做）。
- **JSON Schema 不做完整校验**：仅"参数是否存在"轻量校验 + 透出清单。完整校验引擎超出轻量目标。
- **Token 文件权限**：macOS/Linux chmod 600；Windows 用 ACL 限制当前用户（实现时按平台处理，最低限度保证不世界可读）。
