# Plugin RPC + External API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-session plugin capability registry (inter-plugin RPC) plus a localhost HTTP JSON-RPC server that external callers (and future AI MCP) use — over the same kernel — to create connections, run terminal commands, and invoke plugin capabilities.

**Architecture:** RPC contracts (`Capability`/`CapabilityRegistry`/`CapabilityBus`/`RpcRequest`/`RpcResponse`) live in a new JavaFX-free package of `plugin-api`; per-session routing lives in `plugin-loader`; HTTP JSON-RPC transport lives in a new `api-server` module (JDK `com.sun.net.httpserver`, Gson, zero new external deps); `app` wires it and implements host methods; `ui` adds a Preferences "API" tab; `plugin-demo` registers one example capability. Internal RPC and external transport share one `CapabilityBus` → pass-through is free.

**Tech Stack:** Java 21, JDK `com.sun.net.httpserver`, Gson 2.11.0 (already in parent dependencyManagement), JSON-RPC 2.0, JUnit 5 + Mockito + AssertJ (added by this plan — repo currently has no test framework).

## Global Constraints

- **Backward compatibility is a hard constraint.** Only-add-no-break: never change or remove existing method signatures on `JlShellPlugin` / `PluginContext` / `SshSessionContext` / `PluginView` / existing capability interfaces. New methods are `default`. Old plugin fat-jars must keep working unchanged. Verify by running the app with the existing `plugins/plugin-sysmon` and `plugins/plugin-demo` jars loaded.
- **No new external libraries.** Use JDK `com.sun.net.httpserver` and Gson 2.11.0 (already managed). No Spring, no Netty, no JSON-schema validator lib.
- **`api-server` must not depend on JavaFX.** Exclude `javafx-controls` transitively pulled by `plugin-api`; only import from `com.jlshell.plugin.api.rpc` (the new JavaFX-free package).
- **Build command:** `mvn install -DskipTests -q` compiles; `mvn test` runs tests. Single module test: `mvn test -pl <module> -Dtest=<ClassName>`.
- **`AppSettingsService.get(key)` returns `Optional<String>`** — use the `get(key, defaultValue)` overload for defaults.
- **`SessionId` = `new SessionId(UUID)`**, `SessionId.randomId()`, `.toString()` yields the uuid string. Convert incoming JSON strings with `new SessionId(UUID.fromString(s))`.
- **`CommandRequest(String command, Duration timeout, boolean allocatePty, Map<String,String> environment)`**; `CommandResult(String command, Integer exitCode, String stdout, String stderr, Duration duration)`.
- **Packaging gate:** `build-dist.sh` `detect_modules()` must add `jdk.httpserver`, else the jlink'd JRE throws `NoClassDefFoundError` when the API server starts in the packaged app.
- **Token file:** `~/.jlshell/api.token`, 32 random bytes base64, file mode 600 (POSIX) / owner-only ACL (Windows). Generated on first enable.
- **Comments in Chinese where the surrounding code does** (this repo's convention — read existing comments for design intent).

---

## File Structure

### New module: `api-server`
- `api-server/pom.xml` — module descriptor; deps: `core`, `plugin-api` (with javafx-controls excluded), `gson`, `slf4j-api`; test deps via parent.
- `api-server/src/main/java/com/jlshell/api/server/ApiServer.java` — owns the `HttpServer`, lifecycle, token enforcement; delegates to `MethodDispatcher`.
- `api-server/.../ApiServerConfig.java` — `record ApiServerConfig(int port, String token, boolean enabled)`.
- `api-server/.../ApiTokenStore.java` — load/create `~/.jlshell/api.token`, set file permissions.
- `api-server/.../jsonrpc/JsonRpcRequest.java` — `record JsonRpcRequest(String jsonrpc, Object id, String method, JsonElement params)`.
- `api-server/.../jsonrpc/JsonRpcResponse.java` — `record JsonRpcResponse(String jsonrpc, Object id, JsonElement result, JsonRpcError error)`.
- `api-server/.../jsonrpc/JsonRpcError.java` — `record JsonRpcError(int code, String message, JsonElement data)`.
- `api-server/.../jsonrpc/JsonRpcCodec.java` — Gson encode/decode + JSON-RPC error-code constants.
- `api-server/.../jsonrpc/RpcHandler.java` — `HttpHandler` for `/rpc`: auth, decode, dispatch, encode.
- `api-server/.../dispatch/MethodDispatcher.java` — method name → `MethodHandler`; routes to host methods + capability methods.
- `api-server/.../dispatch/MethodHandler.java` — `@FunctionalInterface MethodHandler { CompletableFuture<JsonElement> handle(JsonElement params) throws Exception; }`.
- `api-server/.../dispatch/HostMethods.java` — interface implemented by `app` (session.*, command.run, api.*).
- `api-server/.../dispatch/CapabilityInvokeMethod.java` — wraps `CapabilityBus.invoke`.
- `api-server/.../dispatch/CapabilityListMethod.java` — wraps `CapabilityBus.listCapabilities`.
- `api-server/.../McpEndpoint.java` — placeholder + TODO; not implemented this round.

### `plugin-api` (new package `com.jlshell.plugin.api.rpc` — JavaFX-free)
- `rpc/CapabilitySpec.java`, `rpc/CapabilityHandler.java`, `rpc/CapabilityContext.java`, `rpc/Capability.java`, `rpc/CapabilityRegistry.java`, `rpc/RpcRequest.java`, `rpc/RpcResponse.java`, `rpc/RpcError.java`, `rpc/CapabilityBus.java`
- `rpc/DefaultCapability.java` — `Capability.builder(name)` impl.
- `rpc/EmptyCapabilityRegistry.java` — `CapabilityRegistry.empty()` impl.

### `plugin-loader`
- `CapabilityRegistryImpl.java`, `CapabilityBusImpl.java`, `SessionPluginSet.java`, `CapabilityContextImpl.java`

### `app`
- `app/.../api/HostMethodsImpl.java`

### Modified
- `plugin-api/.../PluginContext.java` (+1 default method)
- `plugin-loader/.../PluginManager.java` (per-session map), `DefaultPluginContext.java` (sessionId+registry)
- `ui/.../view/PluginsTabView.java`, `TerminalWorkspaceView.java`, `MainWindow.java`, `dialog/PreferencesDialog.java`
- `app/.../AppContext.java`, `app/pom.xml`, `pom.xml` (+module +test deps), `build-dist.sh`, `plugins/plugin-demo/.../ScriptSnippetsPlugin.java`, i18n properties.

---

## Task 1: Add test framework to the build

**Files:**
- Modify: `/Users/edgarliu/Workspaces/JavaProjects/JLShell/pom.xml` (dependencyManagement + properties)
- Modify: `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-loader/pom.xml` (test deps)
- Modify: `/Users/edgarliu/Workspaces/JavaProjects/JLShell/api-server/pom.xml` (created here too — but api-server doesn't exist yet; defer its test deps to Task 8. This task only sets up parent + plugin-loader.)

**Interfaces:**
- Produces: JUnit 5, Mockito, AssertJ available as test deps in any module that declares them. Version properties: `junit.version=5.10.2`, `mockito.version=5.12.0`, `assertj.version=3.26.0`.

- [ ] **Step 1: Add version properties to parent pom**

In `/Users/edgarliu/Workspaces/JavaProjects/JLShell/pom.xml`, inside the existing `<properties>` block (next to the other version properties), add:

```xml
        <junit.version>5.10.2</junit.version>
        <mockito.version>5.12.0</mockito.version>
        <assertj.version>3.26.0</assertj.version>
```

- [ ] **Step 2: Add dependencyManagement entries for test libs**

In the same parent pom, inside `<dependencyManagement><dependencies>`, add (after the gson entry):

```xml
            <!-- Test -->
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-core</artifactId>
                <version>${mockito.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-junit-jupiter</artifactId>
                <version>${mockito.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.assertj</groupId>
                <artifactId>assertj-core</artifactId>
                <version>${assertj.version}</version>
                <scope>test</scope>
            </dependency>
```

- [ ] **Step 3: Add test deps + surefire JUnit5 config to plugin-loader pom**

In `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-loader/pom.xml`, add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
```

The parent pom already configures `maven-surefire-plugin` 3.3.1 (which supports JUnit 5 natively). No surefire version override needed. Confirm surefire config in parent: it should not pin a JUnit4 provider. (Read parent pom around the surefire pluginManagement entry; if it sets `<dependencies>` forcing junit-vintage, remove that. The repo as-is does not, so no action expected.)

- [ ] **Step 4: Write a trivial smoke test to prove the framework works**

Create `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-loader/src/test/java/com/jlshell/plugin/loader/TestFrameworkSmokeTest.java`:

```java
package com.jlshell.plugin.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TestFrameworkSmokeTest {
    @Test
    void frameworkWorks() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
```

- [ ] **Step 5: Run the smoke test**

Run: `mvn test -pl plugin-loader -Dtest=TestFrameworkSmokeTest`
Expected: PASS, "Tests run: 1, Failures: 0". Build succeeds.

- [ ] **Step 6: Commit**

```bash
git add pom.xml plugin-loader/pom.xml plugin-loader/src/test/java/com/jlshell/plugin/loader/TestFrameworkSmokeTest.java
git commit -m "build: add JUnit5 + Mockito + AssertJ test framework"
```

---

## Task 2: RPC contracts in `plugin-api` (JavaFX-free package)

**Files:**
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcError.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcRequest.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcResponse.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilitySpec.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityHandler.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityContext.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/Capability.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/DefaultCapability.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityRegistry.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/EmptyCapabilityRegistry.java`
- Create: `plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityBus.java`
- Modify: `plugin-api/src/main/java/com/jlshell/plugin/api/PluginContext.java`

**Interfaces:**
- Produces (used by later tasks):
  - `RpcRequest(String sessionId, String pluginId, String capability, JsonElement args, String requestId)`
  - `RpcResponse(JsonElement result, RpcError error)`
  - `RpcError(int code, String message, JsonElement data)`
  - `CapabilitySpec(String name, String description, JsonObject inputSchema, boolean requiresSession)`
  - `CapabilityHandler.invoke(JsonElement args, CapabilityContext ctx) throws Exception` → `CompletableFuture<JsonElement>`
  - `CapabilityContext.sessionId()` → `String` (nullable); `.sshSession()` → `Optional<SshSessionContext>`; `.pluginContext()` → `PluginContext`
  - `Capability.pluginId()` / `.spec()` / `.handler()`; `Capability.builder(String name)` → `Builder`
  - `CapabilityRegistry.register(Capability)` / `.unregister(String name)` / `.specs()` → `List<CapabilitySpec>` / `.resolve(String name)` → `Optional<Capability>`; `CapabilityRegistry.empty()` → no-op
  - `CapabilityBus.invoke(RpcRequest)` → `CompletableFuture<RpcResponse>`; `.listCapabilities(String sessionId)` → `List<CapabilitySpec>` (null = global only)
  - `PluginContext.capabilityRegistry()` → `CapabilityRegistry` (default → `empty()`)
- Consumes: `com.google.gson.JsonElement`/`JsonObject` (gson already on plugin-api transitive via javafx? **No** — plugin-api does NOT currently depend on gson. Must add gson to plugin-api pom in this task.)

- [ ] **Step 1: Add gson dependency to plugin-api pom**

In `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-api/pom.xml`, inside `<dependencies>`, add:

```xml
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
        </dependency>
```

(Version inherited from parent dependencyManagement: 2.11.0.)

- [ ] **Step 2: Create `RpcError`, `RpcRequest`, `RpcResponse`**

`plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcError.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * RPC 错误。跨 HTTP 边界时用，message 为英文短描述（机器消费，不做 i18n）。
 */
public record RpcError(int code, String message, JsonElement data) {
    public static RpcError of(int code, String message) {
        return new RpcError(code, message, null);
    }
}
```

`plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcRequest.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * 统一 RPC 请求信封。plugin↔plugin 与外部 HTTP 共用。
 * sessionId 为 null 表示调用全局（无 session）能力。
 */
public record RpcRequest(String sessionId, String pluginId, String capability,
                         JsonElement args, String requestId) {}
```

`plugin-api/src/main/java/com/jlshell/plugin/api/rpc/RpcResponse.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * RPC 响应。成功时 error=null；失败时 result=null。
 */
public record RpcResponse(JsonElement result, RpcError error) {
    public static RpcResponse ok(JsonElement result) { return new RpcResponse(result, null); }
    public static RpcResponse error(RpcError e) { return new RpcResponse(null, e); }
}
```

- [ ] **Step 3: Create `CapabilitySpec`, `CapabilityHandler`, `CapabilityContext`**

`CapabilitySpec.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;

/**
 * 能力规格。name 不含 pluginId（路由时由 host 拼接）。
 * inputSchema 为 JSON Schema，null 表示无参。初版 host 仅做参数存在性校验。
 */
public record CapabilitySpec(String name, String description,
                             JsonObject inputSchema, boolean requiresSession) {}
```

`CapabilityHandler.java`:

```java
package com.jlshell.plugin.api.rpc;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

/** 能力处理器：接收 JSON 参数与上下文，异步返回 JSON 结果。 */
@FunctionalInterface
public interface CapabilityHandler {
    CompletableFuture<JsonElement> invoke(JsonElement args, CapabilityContext ctx) throws Exception;
}
```

`CapabilityContext.java`:

```java
package com.jlshell.plugin.api.rpc;

import java.util.Optional;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;

/** 能力被调用时能拿到的上下文。sessionId 为 null 表示全局能力。 */
public interface CapabilityContext {
    String sessionId();
    Optional<SshSessionContext> sshSession();
    PluginContext pluginContext();
}
```

- [ ] **Step 4: Create `Capability` + `DefaultCapability`**

`Capability.java`:

```java
package com.jlshell.plugin.api.rpc;

/** 一个已注册的能力。pluginId 由 host 在注册时注入，插件不必填。 */
public interface Capability {
    String pluginId();
    CapabilitySpec spec();
    CapabilityHandler handler();
    static Capability.Builder builder(String name) { return new DefaultCapability.Builder(name); }

    interface Builder {
        Builder description(String description);
        Builder inputSchema(com.google.gson.JsonObject schema);
        Builder requiresSession(boolean requires);
        Builder handler(CapabilityHandler handler);
        Capability build();
    }
}
```

`DefaultCapability.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;

/** Capability 的默认实现 + Builder。 */
final class DefaultCapability implements Capability {
    private final String pluginId;
    private final CapabilitySpec spec;
    private final CapabilityHandler handler;

    DefaultCapability(String pluginId, CapabilitySpec spec, CapabilityHandler handler) {
        this.pluginId = pluginId;
        this.spec = spec;
        this.handler = handler;
    }

    @Override public String pluginId() { return pluginId; }
    @Override public CapabilitySpec spec() { return spec; }
    @Override public CapabilityHandler handler() { return handler; }

    static final class Builder implements Capability.Builder {
        private final String name;
        private String description = "";
        private JsonObject inputSchema = null;
        private boolean requiresSession = false;
        private CapabilityHandler handler;

        Builder(String name) { this.name = name; }

        @Override public Builder description(String d) { this.description = d; return this; }
        @Override public Builder inputSchema(JsonObject s) { this.inputSchema = s; return this; }
        @Override public Builder requiresSession(boolean r) { this.requiresSession = r; return this; }
        @Override public Builder handler(CapabilityHandler h) { this.handler = h; return this; }

        @Override
        public Capability build() {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("capability name required");
            if (handler == null) throw new IllegalArgumentException("handler required");
            return new DefaultCapability(null, new CapabilitySpec(name, description, inputSchema, requiresSession), handler);
        }
    }
}
```

Note: `pluginId` is set to `null` by `build()`; the host's `CapabilityRegistryImpl.register` overwrites it with the registering plugin's id (see Task 4). `Capability` is an interface; the host creates a copy with the real pluginId. The registry does this, not the plugin.

- [ ] **Step 5: Create `CapabilityRegistry` + `EmptyCapabilityRegistry`**

`CapabilityRegistry.java`:

```java
package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.Optional;

/** 能力注册表。每个 SSH/本地会话一个实例；另有一个全局实例放无 session 能力。 */
public interface CapabilityRegistry {
    void register(Capability capability);
    void unregister(String name);
    List<CapabilitySpec> specs();
    Optional<Capability> resolve(String name);

    /** no-op 实现：register 丢弃、resolve 永远空。供 PluginContext 默认值用，避免旧插件 NPE。 */
    static CapabilityRegistry empty() { return EmptyCapabilityRegistry.INSTANCE; }
}
```

`EmptyCapabilityRegistry.java`:

```java
package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.Optional;

/** CapabilityRegistry.empty() 的单例 no-op 实现。 */
final class EmptyCapabilityRegistry implements CapabilityRegistry {
    static final EmptyCapabilityRegistry INSTANCE = new EmptyCapabilityRegistry();
    private EmptyCapabilityRegistry() {}
    @Override public void register(Capability c) {}
    @Override public void unregister(String name) {}
    @Override public List<CapabilitySpec> specs() { return List.of(); }
    @Override public Optional<Capability> resolve(String name) { return Optional.empty(); }
}
```

- [ ] **Step 6: Create `CapabilityBus`**

`plugin-api/src/main/java/com/jlshell/plugin/api/rpc/CapabilityBus.java`:

```java
package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 能力总线：按 (sessionId, pluginId, capability) 路由调用。
 * plugin↔plugin 与外部 HTTP server 共用同一总线实例。
 */
public interface CapabilityBus {
    CompletableFuture<RpcResponse> invoke(RpcRequest request);
    List<CapabilitySpec> listCapabilities(String sessionId); // null = 仅全局
}
```

- [ ] **Step 7: Add `capabilityRegistry()` default to `PluginContext`**

In `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-api/src/main/java/com/jlshell/plugin/api/PluginContext.java`, add this method to the interface (after `showNotification`, before the `debug` defaults). Add the import `com.jlshell.plugin.api.rpc.CapabilityRegistry;`:

```java
    /** 该会话的能力注册表。旧插件不调用此方法；default 返回 no-op 空 registry，调用也安全。 */
    default CapabilityRegistry capabilityRegistry() {
        return CapabilityRegistry.empty();
    }
```

- [ ] **Step 8: Compile plugin-api**

Run: `mvn install -pl plugin-api -DskipTests -q`
Expected: BUILD SUCCESS. (Confirms the JavaFX-free rpc package compiles and gson is available.)

- [ ] **Step 9: Write a contract test proving `empty()` is no-op and `Capability.builder` works**

Create `/Users/edgarliu/Workspaces/JavaProjects/JLShell/plugin-api/src/test/java/com/jlshell/plugin/api/rpc/CapabilityContractTest.java`:

```java
package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityContractTest {

    @Test
    void emptyRegistryIsNoOp() {
        CapabilityRegistry empty = CapabilityRegistry.empty();
        empty.register(null);                       // no throw
        empty.unregister("x");                      // no throw
        assertThat(empty.specs()).isEmpty();
        assertThat(empty.resolve("anything")).isEmpty();
    }

    @Test
    void builderProducesCapability() {
        JsonObject schema = new JsonObject();
        Capability cap = Capability.builder("readConfig")
                .description("d")
                .inputSchema(schema)
                .requiresSession(true)
                .handler((args, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(args))
                .build();
        assertThat(cap.spec().name()).isEqualTo("readConfig");
        assertThat(cap.spec().requiresSession()).isTrue();
        assertThat(cap.handler()).isNotNull();
        // pluginId is null until host injects it
        assertThat(cap.pluginId()).isNull();
    }

    @Test
    void builderRequiresNameAndHandler() {
        assertThatThrownBy(() -> Capability.builder("  ").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Capability.builder("ok").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Add test deps to `plugin-api/pom.xml` (junit-jupiter, assertj-core, scope test — same pattern as Task 1 Step 3).

- [ ] **Step 10: Run the contract test**

Run: `mvn test -pl plugin-api -Dtest=CapabilityContractTest`
Expected: PASS, "Tests run: 3, Failures: 0".

- [ ] **Step 11: Install + commit**

```bash
mvn install -pl plugin-api -DskipTests -q
git add plugin-api/pom.xml plugin-api/src/main/java/com/jlshell/plugin/api/rpc/ plugin-api/src/test/java/com/jlshell/plugin/api/rpc/CapabilityContractTest.java plugin-api/src/main/java/com/jlshell/plugin/api/PluginContext.java
git commit -m "feat(plugin-api): add JavaFX-free RPC contracts (Capability/Registry/Bus) + capabilityRegistry() default"
```

---

## Task 3: `CapabilityRegistryImpl` + `CapabilityContextImpl` (plugin-loader)

**Files:**
- Create: `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityRegistryImpl.java`
- Create: `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityContextImpl.java`
- Test: `plugin-loader/src/test/java/com/jlshell/plugin/loader/CapabilityRegistryImplTest.java`

**Interfaces:**
- Consumes: `Capability`, `CapabilitySpec`, `CapabilityRegistry`, `CapabilityContext`, `PluginContext`, `SshSessionContext` (from Task 2)
- Produces:
  - `CapabilityRegistryImpl()` — default ctor; `register(Capability)` injects the registry's owner pluginId (set via `withPluginId(String)` only if needed — see note); `resolve(name)` returns the matching capability; `specs()` lists all; `unregister(name)` removes; `clearForPlugin(String pluginId)` removes all of a plugin's capabilities (used by deactivate cleanup)
  - `CapabilityContextImpl(String sessionId, Optional<SshSessionContext>, PluginContext)`

Note on pluginId injection: A per-session registry holds capabilities from multiple plugins. `register` receives a `Capability` whose `pluginId()` is null (from builder). The registry needs the pluginId to (a) route by pluginId and (b) clean up on deactivate. So `register` takes the pluginId explicitly. Signature: `void register(String pluginId, Capability capability)`. This differs from the `CapabilityRegistry` interface (`register(Capability)`) — `CapabilityRegistryImpl` implements the interface AND adds an overloaded `register(String, Capability)`. The interface's `register(Capability)` will be called by the host's convenience wrapper. **Decision:** the host (`DefaultPluginContext.capabilityRegistry()` returned to plugins) wraps the impl so plugins call `register(Capability)` and the host injects its own pluginId. Implement this in Task 4. For Task 3, `CapabilityRegistryImpl` exposes both: `register(String pluginId, Capability)` (real) and `register(Capability)` (interface — delegates using a stored default pluginId set via `bindPlugin(String)`).

Simpler: make `CapabilityRegistryImpl` per-plugin? No — per-session holds multiple plugins. Keep one registry per session; `register(String pluginId, Capability)` is the real API; the `CapabilityRegistry` interface method `register(Capability)` is satisfied by storing a `currentPluginId` thread-locals-free field is unsafe. **Final decision:** `DefaultPluginContext` (Task 4) returns a small `PluginCapabilityRegistryView` that implements `CapabilityRegistry` and delegates `register(Capability)` → `impl.register(thisPluginId, cap)`. `CapabilityRegistryImpl` itself only implements `register(String pluginId, Capability)` + the read methods, and does NOT implement `CapabilityRegistry.register(Capability)` — instead `CapabilityRegistryImpl implements CapabilityRegistry` with `register(Capability)` throwing `UnsupportedOperationException` (host never calls it directly; the view is the public surface). The `resolve`/`specs`/`unregister`/`clearForPlugin` are the real methods.

To keep it clean: `CapabilityRegistryImpl implements CapabilityRegistry`. `register(Capability)` (interface) delegates to `register(null, cap)`? No. Cleanest: the view binds pluginId. Let me just have `register(String pluginId, Capability)` as an added method and satisfy the interface's `register(Capability)` by requiring the caller to have set pluginId on the capability first. Since `build()` sets it null, the view sets it. **Implement the view in Task 4.** For Task 3, implement `CapabilityRegistryImpl` with `register(String pluginId, Capability)` + read/clear methods, and make `register(Capability)` (interface) call `register(cap.pluginId(), cap)` (so if pluginId is null it registers under null — the view ensures non-null before calling).

- [ ] **Step 1: Write failing test for `CapabilityRegistryImpl`**

Create `plugin-loader/src/test/java/com/jlshell/plugin/loader/CapabilityRegistryImplTest.java`:

```java
package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityRegistryImpl;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryImplTest {

    private Capability echoCap() {
        return Capability.builder("echo").requiresSession(false)
                .handler((args, ctx) -> CompletableFuture.completedFuture(args))
                .build();
    }

    @Test
    void registerAndResolveByPluginAndName() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        assertThat(reg.resolve("com.a", "echo")).isPresent();
        assertThat(reg.resolve("com.b", "echo")).as("different plugin, not found").isEmpty();
    }

    @Test
    void specsListAllWithPluginId() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        reg.register("com.a", Capability.builder("ping").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        reg.register("com.b", Capability.builder("pong").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        assertThat(reg.specs()).hasSize(3);
    }

    @Test
    void clearForPluginRemovesOnlyThatPlugin() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        reg.register("com.b", Capability.builder("pong").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        reg.clearForPlugin("com.a");
        assertThat(reg.resolve("com.a", "echo")).isEmpty();
        assertThat(reg.resolve("com.b", "pong")).isPresent();
    }

    @Test
    void invokeHandlerReturnsJson() throws Exception {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        Capability cap = reg.resolve("com.a", "echo").orElseThrow();
        JsonElement out = cap.handler().invoke(new JsonPrimitive("hi"), null).get();
        assertThat(out.toString()).isEqualTo("\"hi\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl plugin-loader -Dtest=CapabilityRegistryImplTest`
Expected: FAIL — `CapabilityRegistryImpl` does not exist (compile error).

- [ ] **Step 3: Implement `CapabilityRegistryImpl`**

Create `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityRegistryImpl.java`:

```java
package com.jlshell.plugin.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/**
 * 每会话能力注册表。按 (pluginId, name) 存储与查找。
 * 线程安全：内部用 ConcurrentHashMap。
 */
public class CapabilityRegistryImpl implements CapabilityRegistry {

    /** key = pluginId + "/" + capabilityName */
    private final ConcurrentHashMap<String, Capability> byKey = new ConcurrentHashMap<>();

    private static String key(String pluginId, String name) {
        return pluginId + "/" + name;
    }

    /** 真正的注册入口：host 用插件 id 注入。 */
    public void register(String pluginId, Capability capability) {
        if (pluginId == null) throw new IllegalArgumentException("pluginId required");
        String name = capability.spec().name();
        byKey.put(key(pluginId, name), withPluginId(capability, pluginId));
    }

    /** 接口实现：要求 capability 已带 pluginId（由 host view 注入）。 */
    @Override
    public void register(Capability capability) {
        register(capability.pluginId(), capability);
    }

    @Override
    public void unregister(String name) {
        byKey.entrySet().removeIf(e -> e.getKey().endsWith("/" + name));
    }

    public void unregister(String pluginId, String name) {
        byKey.remove(key(pluginId, name));
    }

    /** 停用插件时清掉它的全部能力。 */
    public void clearForPlugin(String pluginId) {
        byKey.entrySet().removeIf(e -> e.getKey().startsWith(pluginId + "/"));
    }

    public Optional<Capability> resolve(String pluginId, String name) {
        return Optional.ofNullable(byKey.get(key(pluginId, name)));
    }

    @Override
    public Optional<Capability> resolve(String name) {
        return byKey.values().stream().filter(c -> c.spec().name().equals(name)).findFirst();
    }

    @Override
    public List<CapabilitySpec> specs() {
        List<CapabilitySpec> out = new ArrayList<>();
        byKey.forEach((k, c) -> out.add(c.spec()));
        return out;
    }

    /** 列出能力清单（含 pluginId，供外部自省/MCP）。 */
    public List<CapabilitySpec> specs(String pluginId) {
        return byKey.entrySet().stream()
                .filter(e -> e.getKey().startsWith(pluginId + "/"))
                .map(e -> e.getValue().spec())
                .toList();
    }

    public List<Capability> capabilities() {
        return List.copyOf(byKey.values());
    }

    private static Capability withPluginId(Capability cap, String pluginId) {
        if (pluginId.equals(cap.pluginId())) return cap;
        CapabilitySpec s = cap.spec();
        return new Capability() {
            @Override public String pluginId() { return pluginId; }
            @Override public CapabilitySpec spec() { return s; }
            @Override public CapabilityHandler handler() { return cap.handler(); }
        };
    }

    // 复用接口的 handler 类型别名
    private interface CapabilityHandler extends com.jlshell.plugin.api.rpc.CapabilityHandler {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl plugin-loader -Dtest=CapabilityRegistryImplTest`
Expected: PASS, "Tests run: 4, Failures: 0".

- [ ] **Step 5: Implement `CapabilityContextImpl`**

Create `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityContextImpl.java`:

```java
package com.jlshell.plugin.loader;

import java.util.Optional;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityContext;

/** CapabilityContext 默认实现。 */
public class CapabilityContextImpl implements CapabilityContext {
    private final String sessionId;
    private final Optional<SshSessionContext> sshSession;
    private final PluginContext pluginContext;

    public CapabilityContextImpl(String sessionId, Optional<SshSessionContext> sshSession, PluginContext pluginContext) {
        this.sessionId = sessionId;
        this.sshSession = sshSession;
        this.pluginContext = pluginContext;
    }

    @Override public String sessionId() { return sessionId; }
    @Override public Optional<SshSessionContext> sshSession() { return sshSession; }
    @Override public PluginContext pluginContext() { return pluginContext; }
}
```

- [ ] **Step 6: Compile + run all plugin-loader tests**

Run: `mvn test -pl plugin-loader`
Expected: all tests PASS (smoke + registry).

- [ ] **Step 7: Commit**

```bash
git add plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityRegistryImpl.java plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityContextImpl.java plugin-loader/src/test/java/com/jlshell/plugin/loader/CapabilityRegistryImplTest.java
git commit -m "feat(plugin-loader): per-session CapabilityRegistry + CapabilityContext impl"
```

---

## Task 4: `PluginManager` per-session tracking + `DefaultPluginContext` wiring

**Files:**
- Modify: `plugin-loader/src/main/java/com/jlshell/plugin/loader/PluginManager.java`
- Modify: `plugin-loader/src/main/java/com/jlshell/plugin/loader/DefaultPluginContext.java`
- Create: `plugin-loader/src/main/java/com/jlshell/plugin/loader/SessionPluginSet.java`
- Create: `plugin-loader/src/main/java/com/jlshell/plugin/loader/PluginCapabilityRegistryView.java`
- Create: `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityBusImpl.java`
- Test: `plugin-loader/src/test/java/com/jlshell/plugin/loader/CapabilityBusImplTest.java`
- Test: `plugin-loader/src/test/java/com/jlshell/plugin/loader/PluginManagerPerSessionTest.java`

**Interfaces:**
- Consumes: `CapabilityRegistryImpl`, `CapabilityContextImpl` (Task 3); `CapabilityBus`, `RpcRequest/Response/Error` (Task 2)
- Produces:
  - `PluginManager.activatePlugin(String id, PluginContext ctx)` — unchanged signature; routes by ctx's sessionId
  - `PluginManager.deactivatePlugin(String sessionId, String pluginId)` — NEW
  - `PluginManager.deactivatePlugin(String pluginId)` — kept (legacy, all sessions)
  - `PluginManager.registryFor(String sessionId)` → `CapabilityRegistryImpl` (for CapabilityBus)
  - `PluginManager.globalRegistry()` → `CapabilityRegistryImpl` (for sessionId=null capabilities)
  - `DefaultPluginContext` new ctor `(String pluginId, String sessionId, CapabilityRegistry registry, Optional<SshSessionContext>, Callbacks)`; `capabilityRegistry()` returns the registry; a backward-compat ctor delegating to empty registry preserved.
  - `CapabilityBusImpl(PluginManager)` implements `CapabilityBus`

- [ ] **Step 1: Write failing test for per-session isolation + bus routing**

Create `plugin-loader/src/test/java/com/jlshell/plugin/loader/PluginManagerPerSessionTest.java`:

```java
package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.rpc.Capability;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class PluginManagerPerSessionTest {

    // 一个会向 registry 注册能力并记录自己 ctx 的最小插件
    static class CapPlugin implements JlShellPlugin {
        final String id;
        PluginContext ctx;
        CapPlugin(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String version() { return "1"; }
        @Override public String description() { return ""; }
        @Override public boolean requiresSshSession() { return false; }
        @Override public void activate(PluginContext ctx) {
            this.ctx = ctx;
            ctx.capabilityRegistry().register(
                Capability.builder("ping").handler((a, c) -> CompletableFuture.completedFuture(a)).build());
        }
        @Override public void deactivate() {}
    }

    private DefaultPluginContext ctxFor(String pluginId, String sessionId) {
        return new DefaultPluginContext(pluginId, sessionId,
                new com.jlshell.plugin.loader.CapabilityRegistryImpl(),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
    }

    @Test
    void samePluginInTwoSessionsIsIsolated() {
        PluginManager mgr = new PluginManager(); // 不加载外部 jar
        // 直接构造插件实例并经 per-session 路径激活
        CapPlugin a1 = new CapPlugin("com.test.cap");
        CapPlugin a2 = new CapPlugin("com.test.cap");
        DefaultPluginContext c1 = ctxFor("com.test.cap", "sess-A");
        DefaultPluginContext c2 = ctxFor("com.test.cap", "sess-B");
        mgr.activateInstance(a1, c1);
        mgr.activateInstance(a2, c2);
        assertThat(a1.ctx).isNotSameAs(a2.ctx);
        // 两个 session 的 registry 互不影响
        assertThat(mgr.registryFor("sess-A").resolve("com.test.cap", "ping")).isPresent();
        assertThat(mgr.registryFor("sess-B").resolve("com.test.cap", "ping")).isPresent();
        // 停 A 不影响 B
        mgr.deactivatePlugin("sess-A", "com.test.cap");
        assertThat(mgr.registryFor("sess-A").resolve("com.test.cap", "ping")).isEmpty();
        assertThat(mgr.registryFor("sess-B").resolve("com.test.cap", "ping")).isPresent();
    }
}
```

Note: this test uses a NEW method `PluginManager.activateInstance(JlShellPlugin, PluginContext)` (bypasses ServiceLoader discovery, for tests + also used by host when it already has a descriptor instance). The existing `activatePlugin(String id, PluginContext)` looks up by id among discovered plugins; we add `activateInstance` to support pre-constructed instances. The host's `PluginsTabView` calls `pluginManager.activatePlugin(item.id(), ctx)` which stays. Tests use `activateInstance`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl plugin-loader -Dtest=PluginManagerPerSessionTest`
Expected: FAIL — `activateInstance`, `registryFor`, new `DefaultPluginContext` ctor, `deactivatePlugin(String,String)` don't exist.

- [ ] **Step 3: Create `SessionPluginSet`**

Create `plugin-loader/src/main/java/com/jlshell/plugin/loader/SessionPluginSet.java`:

```java
package com.jlshell.plugin.loader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginView;

/** 单个会话下激活的插件集合 + 该会话的能力注册表。 */
class SessionPluginSet {
    final String sessionId;
    final CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
    final Map<String, JlShellPlugin> plugins = new ConcurrentHashMap<>();

    SessionPluginSet(String sessionId) { this.sessionId = sessionId; }
}
```

- [ ] **Step 4: Create `PluginCapabilityRegistryView`**

Create `plugin-loader/src/main/java/com/jlshell/plugin/loader/PluginCapabilityRegistryView.java`:

```java
package com.jlshell.plugin.loader;

import java.util.List;
import java.util.Optional;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/**
 * 暴露给单个插件的 registry 视图：把 register(Capability) 绑定到该插件的 id。
 * 这样插件调 ctx.capabilityRegistry().register(cap) 时自动带上自己的 pluginId。
 */
class PluginCapabilityRegistryView implements CapabilityRegistry {
    private final CapabilityRegistryImpl delegate;
    private final String pluginId;

    PluginCapabilityRegistryView(CapabilityRegistryImpl delegate, String pluginId) {
        this.delegate = delegate;
        this.pluginId = pluginId;
    }

    @Override public void register(Capability capability) { delegate.register(pluginId, capability); }
    @Override public void unregister(String name) { delegate.unregister(pluginId, name); }
    @Override public List<CapabilitySpec> specs() { return delegate.specs(pluginId); }
    @Override public Optional<Capability> resolve(String name) { return delegate.resolve(pluginId, name); }
}
```

- [ ] **Step 5: Update `DefaultPluginContext`**

Modify `plugin-loader/src/main/java/com/jlshell/plugin/loader/DefaultPluginContext.java`:
- Add fields `private final String sessionId;` and `private final CapabilityRegistry registry;`
- Add new ctor:
```java
    public DefaultPluginContext(String pluginId, String sessionId,
                                CapabilityRegistry registry,
                                Optional<SshSessionContext> sshSession, Callbacks callbacks) {
        this.pluginId = pluginId;
        this.sessionId = sessionId;
        this.registry = registry;
        this.sshSession = sshSession;
        this.callbacks = callbacks;
    }
```
- Keep existing ctor, make it delegate:
```java
    public DefaultPluginContext(String pluginId, Optional<SshSessionContext> sshSession, Callbacks callbacks) {
        this(pluginId, null, CapabilityRegistry.empty(), sshSession, callbacks);
    }
```
- Add `public String sessionId() { return sessionId; }`
- Override `capabilityRegistry()`:
```java
    @Override
    public CapabilityRegistry capabilityRegistry() {
        return registry;
    }
```
- Add imports: `com.jlshell.plugin.api.rpc.CapabilityRegistry;`

- [ ] **Step 6: Update `PluginManager` to per-session**

Modify `plugin-loader/src/main/java/com/jlshell/plugin/loader/PluginManager.java`:
- Replace `private final Map<String, JlShellPlugin> activePlugins = new ConcurrentHashMap<>();` with:
```java
    private final Map<String, SessionPluginSet> activeBySession = new ConcurrentHashMap<>();
    private final CapabilityRegistryImpl globalRegistry = new CapabilityRegistryImpl();
```
- Add accessors:
```java
    /** 供 CapabilityBus 用：按 sessionId 取该会话的 registry。 */
    public CapabilityRegistryImpl registryFor(String sessionId) {
        if (sessionId == null) return globalRegistry;
        SessionPluginSet set = activeBySession.get(sessionId);
        return set != null ? set.registry : new CapabilityRegistryImpl();
    }

    public CapabilityRegistryImpl globalRegistry() { return globalRegistry; }
```
- Add `activateInstance` (used by tests + host when instance already known):
```java
    public void activateInstance(JlShellPlugin plugin, PluginContext context) {
        ensureLoaded();
        String sid = (context instanceof DefaultPluginContext dpc) ? dpc.sessionId() : null;
        SessionPluginSet set = activeBySession.computeIfAbsent(
                sid == null ? GLOBAL_KEY : sid, SessionPluginSet::new);
        set.plugins.put(plugin.id(), plugin);
        plugin.activate(context);
        log.debug("Activated plugin {} in session {}", plugin.id(), sid);
    }
```
- Add a constant `private static final String GLOBAL_KEY = "__global__";` and have `registryFor(null)` map to globalRegistry (already handled above; ensure `activateInstance` with sid=null uses GLOBAL_KEY bucket whose registry is globalRegistry — but globalRegistry is separate from the GLOBAL_KEY bucket's registry). **Correction:** for global (sessionId=null) capabilities, plugins register into the GLOBAL_KEY bucket's registry. Make `registryFor` return the GLOBAL_KEY bucket's registry when sessionId is null, and have `globalRegistry()` return the same. Simplify: drop the separate `globalRegistry` field; use the `__global__` bucket:
```java
    private static final String GLOBAL_KEY = "__global__";

    public CapabilityRegistryImpl registryFor(String sessionId) {
        String key = (sessionId == null) ? GLOBAL_KEY : sessionId;
        return activeBySession.computeIfAbsent(key, SessionPluginSet::new).registry;
    }
```
Remove the `globalRegistry` field + accessor (or keep `globalRegistry()` returning `registryFor(null)`). Add:
```java
    public CapabilityRegistryImpl globalRegistry() { return registryFor(null); }
```
- Update existing `activatePlugin(String pluginId, PluginContext context)`: instead of putting into a flat map, look up the descriptor instance and delegate to `activateInstance`:
```java
    public void activatePlugin(String pluginId, PluginContext context) {
        ensureLoaded();
        plugins.stream()
                .filter(d -> d.id().equals(pluginId))
                .findFirst()
                .ifPresent(descriptor -> activateInstance(descriptor.instance(), context));
    }
```
- Add per-session deactivate:
```java
    public void deactivatePlugin(String sessionId, String pluginId) {
        SessionPluginSet set = activeBySession.get(sessionId == null ? GLOBAL_KEY : sessionId);
        if (set == null) return;
        JlShellPlugin plugin = set.plugins.remove(pluginId);
        if (plugin != null) {
            set.registry.clearForPlugin(pluginId);
            PluginView view = plugin.view();
            if (view != null) view.onSessionClosed();
            plugin.deactivate();
            log.debug("Deactivated plugin {} in session {}", pluginId, sessionId);
        }
    }
```
- Update legacy `deactivatePlugin(String pluginId)` to stop across all sessions:
```java
    public void deactivatePlugin(String pluginId) {
        activeBySession.values().forEach(set -> {
            JlShellPlugin plugin = set.plugins.remove(pluginId);
            if (plugin != null) {
                set.registry.clearForPlugin(pluginId);
                PluginView view = plugin.view();
                if (view != null) view.onSessionClosed();
                plugin.deactivate();
            }
        });
    }
```
- Update `deactivateAll()`:
```java
    public void deactivateAll() {
        activeBySession.values().forEach(set -> {
            set.plugins.values().forEach(p -> {
                PluginView view = p.view();
                if (view != null) view.onSessionClosed();
                p.deactivate();
            });
            set.plugins.clear();
            set.registry.clearForPlugin(null); // clearForPlugin(null) is a no-op; instead clear all
        });
        activeBySession.clear();
    }
```
Note: `clearForPlugin(null)` would throw (pluginId required). Replace with a `clear()` method on `CapabilityRegistryImpl`:
Add to `CapabilityRegistryImpl`:
```java
    public void clear() { byKey.clear(); }
```
And in `deactivateAll` use `set.registry.clear()`. Also remove the bad `clearForPlugin(null)` line.

- Update `notifyThemeChanged`/`notifyLocaleChanged` to iterate all sessions' plugins:
```java
    private void notifyThemeChanged(String themeName) {
        activeBySession.values().stream().flatMap(s -> s.plugins.values().stream()).forEach(p -> {
            PluginView view = p.view();
            if (view != null) view.onThemeChanged(themeName);
        });
    }
    private void notifyLocaleChanged(Locale locale) {
        activeBySession.values().stream().flatMap(s -> s.plugins.values().stream()).forEach(p -> {
            PluginView view = p.view();
            if (view != null) view.onLocaleChanged(locale);
        });
    }
```
- Remove the old `activePlugins` usages entirely.
- Imports: `com.jlshell.plugin.api.rpc.CapabilityRegistry;` (for the `empty()` usage if any remains — DefaultPluginContext handles it).

- [ ] **Step 7: Make host pass the `PluginCapabilityRegistryView` to plugin contexts**

The plugin's `ctx.capabilityRegistry()` must return the view (bound to pluginId), not the raw per-session registry (which requires pluginId on register). Two options:
(a) `DefaultPluginContext` holds the raw `CapabilityRegistryImpl` and `capabilityRegistry()` returns `new PluginCapabilityRegistryView(registry, pluginId)` each call — cheap.
(b) Construct the view once in the host and pass it as the `registry` arg.

Use (a) for simplicity + correctness: in `DefaultPluginContext.capabilityRegistry()`, if `registry` is a `CapabilityRegistryImpl`, return a view bound to `pluginId`; else return `registry` as-is (covers the `empty()` legacy ctor). Modify `DefaultPluginContext.capabilityRegistry()`:

```java
    @Override
    public CapabilityRegistry capabilityRegistry() {
        if (registry instanceof CapabilityRegistryImpl impl) {
            return new PluginCapabilityRegistryView(impl, pluginId);
        }
        return registry;
    }
```

This means the `registry` field stored in `DefaultPluginContext` should be the per-session `CapabilityRegistryImpl` (not the view). The host constructs `DefaultPluginContext(pluginId, sessionId, sessionRegistryImpl, sshSession, callbacks)`. Update Task 5/6 host call sites accordingly.

- [ ] **Step 8: Create `CapabilityBusImpl`**

Create `plugin-loader/src/main/java/com/jlshell/plugin/loader/CapabilityBusImpl.java`:

```java
package com.jlshell.plugin.loader;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityContext;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;

/**
 * CapabilityBus 实现：按 (sessionId, pluginId, capability) 路由。
 * 依赖 PluginManager 的 per-session registry。
 */
public class CapabilityBusImpl implements CapabilityBus {

    private static final int CODE_METHOD_NOT_FOUND = -32601;
    private static final int CODE_INTERNAL = -32603;

    private final PluginManager pluginManager;

    public CapabilityBusImpl(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public CompletableFuture<RpcResponse> invoke(RpcRequest req) {
        if (req.pluginId() == null || req.capability() == null) {
            return CompletableFuture.completedFuture(
                    RpcResponse.error(RpcError.of(CODE_METHOD_NOT_FOUND, "pluginId and capability required")));
        }
        CapabilityRegistryImpl reg = pluginManager.registryFor(req.sessionId());
        Capability cap = reg.resolve(req.pluginId(), req.capability()).orElse(null);
        if (cap == null) {
            return CompletableFuture.completedFuture(RpcResponse.error(
                    RpcError.of(CODE_METHOD_NOT_FOUND,
                            "capability not found: " + req.pluginId() + "/" + req.capability())));
        }
        // 找到该插件的 PluginContext 以构造 CapabilityContext
        PluginContext pluginCtx = pluginManager.contextFor(req.sessionId(), req.pluginId());
        Optional<SshSessionContext> ssh = (pluginCtx instanceof DefaultPluginContext dpc)
                ? dpc.sshSession() : Optional.empty();
        CapabilityContext capCtx = new CapabilityContextImpl(req.sessionId(), ssh, pluginCtx);
        try {
            return cap.handler().invoke(req.args() == null ? com.google.gson.JsonNull.INSTANCE : req.args(), capCtx)
                    .thenApply(RpcResponse::ok)
                    .exceptionally(t -> RpcResponse.error(
                            RpcError.of(CODE_INTERNAL, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(RpcResponse.error(
                    RpcError.of(CODE_INTERNAL, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    @Override
    public List<CapabilitySpec> listCapabilities(String sessionId) {
        return pluginManager.registryFor(sessionId).specs();
    }
}
```

Add to `PluginManager`:
```java
    /** 供 CapabilityBus 构造 CapabilityContext 时取插件的 PluginContext。 */
    public PluginContext contextFor(String sessionId, String pluginId) {
        SessionPluginSet set = activeBySession.get(sessionId == null ? GLOBAL_KEY : sessionId);
        // PluginContext 不直接存在 set 里；host 激活时需记下。见下。
        return null; // 占位，Task 5 由 host 记录后补全
    }
```
**Problem:** the `PluginContext` is not stored in `SessionPluginSet`. The host passes it to `activate()` but `PluginManager` doesn't keep it. Add storage: in `SessionPluginSet` add `final Map<String, PluginContext> contexts = new ConcurrentHashMap<>();` and in `activateInstance` store `set.contexts.put(plugin.id(), context)`. Then `contextFor` returns it. Update Step 6's `activateInstance`:
```java
        set.contexts.put(plugin.id(), context);
```
And `SessionPluginSet` add the `contexts` map + clear it in deactivate. Update `contextFor`:
```java
    public PluginContext contextFor(String sessionId, String pluginId) {
        SessionPluginSet set = activeBySession.get(sessionId == null ? GLOBAL_KEY : sessionId);
        return set == null ? null : set.contexts.get(pluginId);
    }
```
And in both `deactivatePlugin(...)` add `set.contexts.remove(pluginId);` after deactivate.

- [ ] **Step 9: Write `CapabilityBusImplTest`**

Create `plugin-loader/src/test/java/com/jlshell/plugin/loader/CapabilityBusImplTest.java`:

```java
package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityBusImpl;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityBusImplTest {

    private DefaultPluginContext ctxFor(String pluginId, String sessionId) {
        return new DefaultPluginContext(pluginId, sessionId, new CapabilityRegistryImpl(),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
    }

    @Test
    void invokeRegisteredCapabilityReturnsResult() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor("com.a", "s1");
        ctx.capabilityRegistry().register(
                Capability.builder("echo").handler((a, c) -> CompletableFuture.completedFuture(new JsonPrimitive("pong"))).build());
        // 模拟 host 已激活：把 ctx 注册进 manager 的 session bucket
        mgr.adoptContext("s1", "com.a", ctx);

        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "echo", new JsonPrimitive("ping"), "r1")).get();
        assertThat(resp.error()).isNull();
        assertThat(resp.result().getAsString()).isEqualTo("pong");
    }

    @Test
    void unknownCapabilityReturnsMethodNotFound() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "nope", null, "r1")).get();
        assertThat(resp.result()).isNull();
        assertThat(resp.error().code()).isEqualTo(-32601);
    }

    @Test
    void handlerThrowingReturnsInternalError() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor("com.a", "s1");
        ctx.capabilityRegistry().register(
                Capability.builder("boom").handler((a, c) -> { throw new IllegalStateException("kaboom"); }).build());
        mgr.adoptContext("s1", "com.a", ctx);
        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "boom", null, "r1")).get();
        assertThat(resp.error().code()).isEqualTo(-32603);
        assertThat(resp.error().message()).isEqualTo("kaboom");
    }
}
```

This needs `PluginManager.adoptContext(String sessionId, String pluginId, PluginContext ctx)` — a test/host helper that registers an already-constructed context into a session bucket (used when the host builds the context itself then activates). Add to `PluginManager`:
```java
    /** 供 host 在自建 context 后挂到某 session 桶（激活由 host 完成）。 */
    public void adoptContext(String sessionId, String pluginId, PluginContext ctx) {
        SessionPluginSet set = activeBySession.computeIfAbsent(
                sessionId == null ? GLOBAL_KEY : sessionId, SessionPluginSet::new);
        set.contexts.put(pluginId, ctx);
    }
```

- [ ] **Step 10: Run all plugin-loader tests**

Run: `mvn test -pl plugin-loader`
Expected: all PASS (smoke, registry, per-session, bus).

- [ ] **Step 11: Install + commit**

```bash
mvn install -pl plugin-loader -DskipTests -q
git add plugin-loader/
git commit -m "feat(plugin-loader): per-session plugin tracking + CapabilityBusImpl (fixes cross-session collision)"
```

---

## Task 5: Update host call sites (`PluginsTabView`, `TerminalWorkspaceView`, `MainWindow`)

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/PluginsTabView.java`
- Modify: `ui/src/main/java/com/jlshell/ui/view/TerminalWorkspaceView.java`
- Modify: `ui/src/main/java/com/jlshell/ui/view/MainWindow.java`
- Modify: `ui/src/main/java/com/jlshell/ui/view/SessionWorkspaceTab.java` (pass sessionId to PluginsTabView/TerminalWorkspaceView)

**Interfaces:**
- Consumes: new `DefaultPluginContext(pluginId, sessionId, registry, sshSession, callbacks)` ctor; `PluginManager.deactivatePlugin(String, String)`; `PluginManager.registryFor(String)`; `PluginManager.adoptContext(String, String, PluginContext)`
- Produces: each session tab constructs a per-session `CapabilityRegistryImpl`, builds `DefaultPluginContext` with `(pluginId, sessionId, registry, sshCtx, callbacks)`, activates via existing `activatePlugin`, and `stopPlugins()` calls `deactivatePlugin(sessionId, pluginId)`. Local-shell tabs use a synthetic `"local-"+uuid` sessionId.

- [ ] **Step 1: Determine sessionId at each tab**

In `SessionWorkspaceTab`, the SSH session id is `sshSession.sessionId().toString()`. Pass it down to `TerminalWorkspaceView` and `PluginsTabView` (they currently get `sshSession` and derive nothing). Add a `String sessionId` param to `PluginsTabView` ctor and `TerminalWorkspaceView` is already constructed in `SessionWorkspaceTab` with `sshSession` — add a `sessionId()` accessor or pass it.

Read `SessionWorkspaceTab.java` (already in context from earlier session): it constructs `TerminalWorkspaceView(sshSession, ...)` and `PluginsTabView(pluginManager, sshSession, workspaceTabs, ...)`. Add `String sessionId` param to both. In `SessionWorkspaceTab`, compute `String sessionId = sshSession.sessionId().toString();` and pass it.

For local-shell tabs (`MainWindow.openLocalShellTab`), generate `String sessionId = "local-" + java.util.UUID.randomUUID();` and pass into the local-shell tab's plugin views. Read `MainWindow.java:889` `openLocalShellTab` to see how local shell tab is built (it may not use `SessionWorkspaceTab`/`PluginsTabView` — check). If local-shell tabs don't currently host plugins, skip local-shell sessionId and only wire SSH tabs. **Action:** read `MainWindow.openLocalShellTab` first.

- [ ] **Step 2: Read `MainWindow.openLocalShellTab` and `TerminalWorkspaceView` ctor**

Read `/Users/edgarliu/Workspaces/JavaProjects/JLShell/ui/src/main/java/com/jlshell/ui/view/MainWindow.java` lines 860-918 and `TerminalWorkspaceView.java` lines 1-60 (ctor). Confirm whether local-shell tabs instantiate `PluginsTabView`/`TerminalWorkspaceView` with a `SshSession`.

- [ ] **Step 3: Update `PluginsTabView` to use per-session registry + sessionId**

In `PluginsTabView.java`:
- Add `String sessionId` field + ctor param.
- In the open-button handler, replace the `DefaultPluginContext` construction:
```java
                        CapabilityRegistryImpl sessionRegistry = pluginManager.registryFor(sessionId);
                        DefaultPluginContext ctx = new DefaultPluginContext(item.id(), sessionId, sessionRegistry, sshCtx, new DefaultPluginContext.Callbacks() {
                            // ... existing openTab/closeTab/updateTabTitle/resolveI18n unchanged ...
                        });
                        ctx.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
                        ctx.writableLocaleProperty().bind(pluginManager.localeProperty());
                        pluginManager.adoptContext(sessionId, item.id(), ctx);
                        pluginManager.activatePlugin(item.id(), ctx);
                        activatedPluginIds.add(item.id());
```
  (Note: `adoptContext` before `activatePlugin` so the bus can find the context once the plugin registers capabilities during activate.)
- `stopPlugins()`:
```java
    public void stopPlugins() {
        activatedPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedPluginIds.clear();
    }
```
- Imports: `com.jlshell.plugin.loader.CapabilityRegistryImpl;`

- [ ] **Step 4: Update `TerminalWorkspaceView` quick-launch path identically**

In `TerminalWorkspaceView.java` `activatePlugin(PluginDescriptor desc)` (around line 778): add `String sessionId` field (set from ctor). Same changes as Step 3 (per-session registry, `adoptContext`, new `DefaultPluginContext` ctor, `stopPlugins` uses `deactivatePlugin(sessionId, id)`).

- [ ] **Step 5: Thread `sessionId` from `SessionWorkspaceTab`**

In `SessionWorkspaceTab.java`: compute `String sessionId = sshSession.sessionId().toString();` and pass to `TerminalWorkspaceView` ctor (add param) and to `PluginsTabView` ctor (add param). Update both ctors' signatures.

- [ ] **Step 6: Local-shell tabs — read and decide**

If `openLocalShellTab` builds a `TerminalWorkspaceView`/`PluginsTabView` with a null `SshSession`, pass `sessionId = "local-" + UUID.randomUUID()`. If local-shell tabs don't host plugins, leave them but ensure no NPE (their `SshSessionContext` is empty → `Optional.empty()`, fine). Apply the minimal change needed; do not add plugin hosting to local-shell if it didn't have it.

- [ ] **Step 7: Compile the whole project**

Run: `mvn install -DskipTests -q`
Expected: BUILD SUCCESS. (No behavioral test here — UI; manual test in Task 12.)

- [ ] **Step 8: Commit**

```bash
git add ui/
git commit -m "feat(ui): wire per-session capability registry into PluginsTabView/TerminalWorkspaceView"
```

---

## Task 6: Create `api-server` module (skeleton + JSON-RPC codec)

**Files:**
- Create: `api-server/pom.xml`
- Create: `api-server/src/main/java/com/jlshell/api/server/ApiServerConfig.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/jsonrpc/JsonRpcError.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/jsonrpc/JsonRpcRequest.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/jsonrpc/JsonRpcResponse.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/jsonrpc/JsonRpcCodec.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/dispatch/MethodHandler.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/dispatch/MethodDispatcher.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/dispatch/HostMethods.java`
- Modify: `pom.xml` (add `<module>api-server</module>`)
- Test: `api-server/src/test/java/com/jlshell/api/server/jsonrpc/JsonRpcCodecTest.java`

**Interfaces:**
- Consumes: Gson (parent managed), `CapabilityBus` + `RpcRequest/Response/Error` (Task 2)
- Produces:
  - `ApiServerConfig(int port, String token, boolean enabled)`
  - `JsonRpcRequest(String jsonrpc, Object id, String method, JsonElement params)`
  - `JsonRpcResponse(String jsonrpc, Object id, JsonElement result, JsonRpcError error)`
  - `JsonRpcError(int code, String message, JsonElement data)`
  - `JsonRpcCodec` — `JsonRpcRequest parse(String)`; `String encode(JsonRpcResponse)`; error-code constants: `PARSE_ERROR=-32700, INVALID_REQUEST=-32600, METHOD_NOT_FOUND=-32601, INVALID_PARAMS=-32602, INTERNAL_ERROR=-32603, HOST_ERROR=-32000`
  - `MethodHandler` — `CompletableFuture<JsonElement> handle(JsonElement params) throws Exception`
  - `MethodDispatcher` — `void register(String method, MethodHandler)`; `CompletableFuture<JsonElement> dispatch(String method, JsonElement params)` (returns failed future with METHOD_NOT_FOUND for unknown)
  - `HostMethods` — interface with `CompletableFuture<JsonElement> connect(JsonObject p)` / `disconnect` / `list` / `info` / `runCommand` / `token` / `methods` (implemented by `app` in Task 9)

- [ ] **Step 1: Add module to parent pom**

In `/Users/edgarliu/Workspaces/JavaProjects/JLShell/pom.xml` `<modules>`, add `<module>api-server</module>` (after `<module>plugin-loader</module>`).

- [ ] **Step 2: Create `api-server/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.jlshell</groupId>
        <artifactId>jlshell-parent</artifactId>
        <version>0.1.0.RELEASE</version>
    </parent>
    <artifactId>api-server</artifactId>
    <name>JLShell External API Server</name>
    <dependencies>
        <dependency>
            <groupId>com.jlshell</groupId>
            <artifactId>core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.jlshell</groupId>
            <artifactId>plugin-api</artifactId>
            <version>${project.version}</version>
            <exclusions>
                <!-- api-server 不依赖 JavaFX；只用 rpc 契约包 -->
                <exclusion>
                    <groupId>org.openjfx</groupId>
                    <artifactId>*</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <!-- test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Risk check:** excluding all `org.openjfx` from `plugin-api` — but `plugin-api`'s `PluginContext`/`SshSessionContext` reference `javafx.scene.Node`/`ReadOnlyStringProperty`. `api-server` only imports `com.jlshell.plugin.api.rpc.*` (which uses only Gson). Class loading is lazy; as long as api-server code never touches the JavaFX-referencing classes, it compiles and runs. **However**, excluding javafx means those classes are simply absent from api-server's compile classpath → api-server code that references them won't compile (good, enforced). Verify the rpc package compiles without javafx (Task 2 Step 8 already did). If `plugin-api` main source has a package-level dependency forcing javafx at compile of the *whole* jar, the exclusion only affects api-server's transitive resolution, not plugin-api's own compilation (plugin-api still compiles with javafx). So api-server compiling against the rpc package only is fine. **Verify in Step 9.**

- [ ] **Step 3: Create `ApiServerConfig`**

`api-server/src/main/java/com/jlshell/api/server/ApiServerConfig.java`:

```java
package com.jlshell.api.server;

/** API server 配置。port=0 表示自动选空闲端口。 */
public record ApiServerConfig(int port, String token, boolean enabled) {}
```

- [ ] **Step 4: Create JSON-RPC records + codec**

`jsonrpc/JsonRpcError.java`:

```java
package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcError(int code, String message, JsonElement data) {
    public static JsonRpcError of(int code, String message) { return new JsonRpcError(code, message, null); }
}
```

`jsonrpc/JsonRpcRequest.java`:

```java
package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonElement params) {}
```

`jsonrpc/JsonRpcResponse.java`:

```java
package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcResponse(String jsonrpc, Object id, JsonElement result, JsonRpcError error) {
    public static JsonRpcResponse ok(Object id, JsonElement result) { return new JsonRpcResponse("2.0", id, result, null); }
    public static JsonRpcResponse err(Object id, JsonRpcError e) { return new JsonRpcResponse("2.0", id, null, e); }
}
```

`jsonrpc/JsonRpcCodec.java`:

```java
package com.jlshell.api.server.jsonrpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** JSON-RPC 2.0 编解码 + 错误码常量。 */
public final class JsonRpcCodec {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HOST_ERROR = -32000;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private JsonRpcCodec() {}

    public static JsonRpcRequest parse(String body) {
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        String jsonrpc = obj.has("jsonrpc") ? obj.get("jsonrpc").getAsString() : null;
        Object id = obj.has("id") && !obj.get("id").isJsonNull() ? gsonId(obj.get("id")) : null;
        String method = obj.has("method") ? obj.get("method").getAsString() : null;
        JsonElement params = obj.has("params") ? obj.get("params") : null;
        return new JsonRpcRequest(jsonrpc, id, method, params);
    }

    public static String encode(JsonRpcResponse resp) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (resp.id() != null) o.add("id", idToJson(resp.id()));
        else o.add("id", com.google.gson.JsonNull.INSTANCE);
        if (resp.error() != null) {
            JsonObject e = new JsonObject();
            e.addProperty("code", resp.error().code());
            e.addProperty("message", resp.error().message());
            if (resp.error().data() != null) e.add("data", resp.error().data());
            o.add("error", e);
        } else {
            o.add("result", resp.result() == null ? com.google.gson.JsonNull.INSTANCE : resp.result());
        }
        return GSON.toJson(o);
    }

    private static Object gsonId(JsonElement el) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isString()) return p.getAsString();
        }
        return el.toString(); // 保留原始
    }

    private static JsonElement idToJson(Object id) {
        if (id instanceof Number n) return new JsonPrimitive(n);
        if (id instanceof String s) return new JsonPrimitive(s);
        return new JsonPrimitive(String.valueOf(id));
    }
}
```

- [ ] **Step 5: Create `MethodHandler` + `MethodDispatcher`**

`dispatch/MethodHandler.java`:

```java
package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

@FunctionalInterface
public interface MethodHandler {
    CompletableFuture<JsonElement> handle(JsonElement params) throws Exception;
}
```

`dispatch/MethodDispatcher.java`:

```java
package com.jlshell.api.server.dispatch;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonElement;
import com.jlshell.api.server.jsonrpc.JsonRpcError;

/** method 名 → handler 路由。未知 method 返回带 METHOD_NOT_FOUND 的 failed future。 */
public class MethodDispatcher {
    private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();

    public void register(String method, MethodHandler handler) { handlers.put(method, handler); }

    public CompletableFuture<JsonElement> dispatch(String method, JsonElement params) {
        MethodHandler h = handlers.get(method);
        if (h == null) {
            return CompletableFuture.failedFuture(new MethodNotFoundException(method));
        }
        try {
            return h.handle(params);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 未知 method 的异常，供 RpcHandler 映射成 JSON-RPC error。 */
    public static class MethodNotFoundException extends RuntimeException {
        public final int code = JsonRpcError.of(-32601, "").code();
        public MethodNotFoundException(String method) { super("method not found: " + method); }
    }
}
```

- [ ] **Step 6: Create `HostMethods` interface**

`dispatch/HostMethods.java`:

```java
package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

/**
 * 内置 host method 接口，由 app 实现。不经 CapabilityBus，直接调 core 服务。
 */
public interface HostMethods {
    CompletableFuture<JsonElement> sessionConnect(JsonElement params);
    CompletableFuture<JsonElement> sessionDisconnect(JsonElement params);
    CompletableFuture<JsonElement> sessionList(JsonElement params);
    CompletableFuture<JsonElement> sessionInfo(JsonElement params);
    CompletableFuture<JsonElement> commandRun(JsonElement params);
    CompletableFuture<JsonElement> apiToken(JsonElement params);
    CompletableFuture<JsonElement> apiMethods(JsonElement params);
}
```

- [ ] **Step 7: Write codec test**

`api-server/src/test/java/com/jlshell/api/server/jsonrpc/JsonRpcCodecTest.java`:

```java
package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcCodecTest {
    @Test
    void parsesRequestWithParams() {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "session.connect");
        JsonObject params = new JsonObject(); params.addProperty("connectionId", "c1");
        body.add("params", params);
        JsonRpcRequest req = JsonRpcCodec.parse(body.toString());
        assertThat(req.method()).isEqualTo("session.connect");
        assertThat(req.params().getAsJsonObject().get("connectionId").getAsString()).isEqualTo("c1");
    }

    @Test
    void encodesSuccessResponse() {
        JsonRpcResponse resp = JsonRpcResponse.ok(1, new JsonPrimitive("ok"));
        String json = JsonRpcCodec.encode(resp);
        assertThat(json).contains("\"result\":\"ok\"").contains("\"id\":1");
    }

    @Test
    void encodesErrorResponse() {
        JsonRpcResponse resp = JsonRpcResponse.err(2, JsonRpcError.of(-32601, "nope"));
        String json = JsonRpcCodec.encode(resp);
        assertThat(json).contains("\"error\"").contains("-32601").contains("nope");
    }
}
```

- [ ] **Step 8: Run codec test**

Run: `mvn test -pl api-server -Dtest=JsonRpcCodecTest`
Expected: PASS, "Tests run: 3".

- [ ] **Step 9: Verify api-server compiles WITHOUT JavaFX on classpath**

Run: `mvn dependency:tree -pl api-server -Dincludes=org.openjfx 2>&1 | grep -c openjfx`
Expected: `0` (no openjfx in api-server's resolved deps). If non-zero, the exclusion didn't take — recheck the pom exclusion (must exclude each javafx artifact or use `*`).

- [ ] **Step 10: Install + commit**

```bash
mvn install -pl api-server -DskipTests -q
git add pom.xml api-server/
git commit -m "feat(api-server): new module skeleton + JSON-RPC 2.0 codec + method dispatcher"
```

---

## Task 7: Capability method handlers + `ApiServer` HTTP server

**Files:**
- Create: `api-server/src/main/java/com/jlshell/api/server/dispatch/CapabilityInvokeMethod.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/dispatch/CapabilityListMethod.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/ApiServer.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/jsonrpc/RpcHandler.java`
- Create: `api-server/src/main/java/com/jlshell/api/server/McpEndpoint.java`
- Test: `api-server/src/test/java/com/jlshell/api/server/ApiServerTest.java`

**Interfaces:**
- Consumes: `CapabilityBus` (Task 2), `MethodDispatcher`/`MethodHandler`/`HostMethods`/`JsonRpcCodec` (Task 6)
- Produces:
  - `ApiServer(ApiServerConfig, CapabilityBus, HostMethods, Gson)` — `start()` / `stop()` / `int port()` / `String token()`
  - `RpcHandler` — `HttpHandler` enforcing bearer token + POST + JSON, decoding via codec, dispatching, encoding response, mapping exceptions to JSON-RPC errors.

- [ ] **Step 1: Create `CapabilityInvokeMethod` + `CapabilityListMethod`**

`dispatch/CapabilityInvokeMethod.java`:

```java
package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;

/** capability.invoke method：把 JSON-RPC params 透传成 RpcRequest 调 CapabilityBus。 */
public class CapabilityInvokeMethod implements MethodHandler {
    private final CapabilityBus bus;
    public CapabilityInvokeMethod(CapabilityBus bus) { this.bus = bus; }

    @Override
    public CompletableFuture<JsonElement> handle(JsonElement params) {
        JsonObject p = params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject();
        String sessionId = p.has("sessionId") && !p.get("sessionId").isJsonNull() ? p.get("sessionId").getAsString() : null;
        String pluginId = p.has("pluginId") ? p.get("pluginId").getAsString() : null;
        String capability = p.has("capability") ? p.get("capability").getAsString() : null;
        JsonElement args = p.has("args") ? p.get("args") : null;
        String requestId = p.has("requestId") ? p.get("requestId").getAsString() : null;
        if (pluginId == null || capability == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("pluginId and capability required"));
        }
        RpcRequest req = new RpcRequest(sessionId, pluginId, capability, args, requestId);
        return bus.invoke(req).thenApply(RpcResponse::result);
        // 注意：bus 返回的 RpcResponse.error 不抛异常；result 可能为 null。
        // 调用方拿到 null result 表示能力层报错——这里把 error 也带回更友好，见下。
    }
}
```

Refinement: returning only `result` drops the `RpcError`. Better: if `RpcResponse.error != null`, fail the future so `RpcHandler` maps it. Update:

```java
        return bus.invoke(req).thenCompose(r -> {
            if (r.error() != null) {
                return CompletableFuture.failedFuture(new CapabilityErrorException(r.error().code(), r.error().message()));
            }
            return CompletableFuture.completedFuture(r.result());
        });
```

Add a small exception type in the same file (or a shared file):
```java
    public static class CapabilityErrorException extends RuntimeException {
        public final int code;
        public CapabilityErrorException(int code, String message) { super(message); this.code = code; }
    }
```

`dispatch/CapabilityListMethod.java`:

```java
package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/** capability.list method：返回某 session 的能力清单。 */
public class CapabilityListMethod implements MethodHandler {
    private final CapabilityBus bus;
    public CapabilityListMethod(CapabilityBus bus) { this.bus = bus; }

    @Override
    public CompletableFuture<JsonElement> handle(JsonElement params) {
        String sessionId = null;
        if (params != null && params.isJsonObject()) {
            JsonObject p = params.getAsJsonObject();
            sessionId = p.has("sessionId") && !p.get("sessionId").isJsonNull() ? p.get("sessionId").getAsString() : null;
        }
        JsonArray arr = new JsonArray();
        for (CapabilitySpec spec : bus.listCapabilities(sessionId)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", spec.name());
            o.addProperty("description", spec.description() == null ? "" : spec.description());
            o.addProperty("requiresSession", spec.requiresSession());
            if (spec.inputSchema() != null) o.add("inputSchema", spec.inputSchema());
            arr.add(o);
        }
        return CompletableFuture.completedFuture(arr);
    }
}
```

- [ ] **Step 2: Create `RpcHandler`**

`jsonrpc/RpcHandler.java`:

```java
package com.jlshell.api.server.jsonrpc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.jlshell.api.server.dispatch.MethodDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** /rpc 端点：bearer token 鉴权 + POST + JSON → dispatch → 编码响应。 */
public class RpcHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(RpcHandler.class);
    private final String token;
    private final MethodDispatcher dispatcher;

    public RpcHandler(String token, MethodDispatcher dispatcher) {
        this.token = token; this.dispatcher = dispatcher;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, JsonRpcCodec.encode(JsonRpcResponse.err(null,
                        JsonRpcError.of(JsonRpcCodec.INVALID_REQUEST, "POST required"))));
                return;
            }
            if (!checkToken(exchange)) {
                write(exchange, 401, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"unauthorized\"}}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonRpcRequest req;
            try { req = JsonRpcCodec.parse(body); }
            catch (Exception e) {
                write(exchange, 200, JsonRpcCodec.encode(JsonRpcResponse.err(null,
                        JsonRpcError.of(JsonRpcCodec.PARSE_ERROR, "parse error: " + e.getMessage()))));
                return;
            }
            if (req.method() == null) {
                write(exchange, 200, JsonRpcCodec.encode(JsonRpcResponse.err(req.id(),
                        JsonRpcError.of(JsonRpcCodec.INVALID_REQUEST, "method required"))));
                return;
            }
            dispatcher.dispatch(req.method(), req.params())
                    .whenComplete((result, err) -> {
                        try {
                            String resp;
                            int status = 200;
                            if (err != null) {
                                int code = (err instanceof MethodDispatcher.MethodNotFoundException) ? JsonRpcCodec.METHOD_NOT_FOUND
                                        : (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
                                            ? mapCause(err.getCause()) : JsonRpcCodec.INTERNAL_ERROR;
                                String msg = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
                                resp = JsonRpcCodec.encode(JsonRpcResponse.err(req.id(), JsonRpcError.of(code, msg)));
                            } else {
                                resp = JsonRpcCodec.encode(JsonRpcResponse.ok(req.id(), result == null ? JsonNull.INSTANCE : result));
                            }
                            write(exchange, status, resp);
                        } catch (IOException ioe) {
                            log.warn("Failed to write RPC response", ioe);
                        }
                    });
        } catch (Exception e) {
            log.error("RPC handler error", e);
            write(exchange, 500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
        }
    }

    private static int mapCause(Throwable cause) {
        // CapabilityInvokeMethod.CapabilityErrorException 携带能力层错误码
        if (cause.getClass().getSimpleName().equals("CapabilityErrorException")) {
            try { return (int) cause.getClass().getMethod("code").getDefaultValue(); } catch (Exception ignored) {}
        }
        if (cause instanceof IllegalArgumentException) return JsonRpcCodec.INVALID_PARAMS;
        return JsonRpcCodec.INTERNAL_ERROR;
    }
```
`mapCause` reflection is fragile. **Replace** with a clean approach: `CapabilityInvokeMethod.CapabilityErrorException` is `public static` and in an api-server package, so import it directly:
```java
import com.jlshell.api.server.dispatch.CapabilityInvokeMethod.CapabilityErrorException;
...
    private static int mapCause(Throwable cause) {
        if (cause instanceof CapabilityErrorException c) return c.code;
        if (cause instanceof IllegalArgumentException) return JsonRpcCodec.INVALID_PARAMS;
        return JsonRpcCodec.INTERNAL_ERROR;
    }
```
Make `CapabilityErrorException` `public` (already `public static` in Step 1). Finish `RpcHandler`:
```java
    private boolean checkToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth != null && ("Bearer " + token).equals(auth);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
```
Also handle the async write: `whenComplete` runs on a future-completion thread, not the HttpExchange's thread. `com.sun.net.httpserver` requires the response be sent on the same exchange — sending from another thread is allowed (the exchange object is thread-safe for a single response). This works but the exchange must not have been closed. Do NOT call `exchange.close()` in the synchronous path. Add `exchange.close()` after write inside `write()`? `sendResponseHeaders` + writing body then `close()` is the norm. Add `exchange.close()` at end of `write`. But `whenComplete`'s write also needs close — same `write()` handles it. Ensure `write` closes: append `exchange.close();` at end of `write()`.

- [ ] **Step 3: Create `ApiServer`**

`api-server/src/main/java/com/jlshell/api/server/ApiServer.java`:

```java
package com.jlshell.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.jlshell.api.server.dispatch.CapabilityInvokeMethod;
import com.jlshell.api.server.dispatch.CapabilityListMethod;
import com.jlshell.api.server.dispatch.HostMethods;
import com.jlshell.api.server.dispatch.MethodDispatcher;
import com.jlshell.api.server.jsonrpc.RpcHandler;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 外部 API server：JDK HttpServer，绑 127.0.0.1，bearer token。 */
public final class ApiServer {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private final ApiServerConfig config;
    private final MethodDispatcher dispatcher;
    private HttpServer httpServer;
    private int actualPort = -1;

    public ApiServer(ApiServerConfig config, CapabilityBus bus, HostMethods hostMethods) {
        this.config = config;
        this.dispatcher = new MethodDispatcher();
        // 透传插件能力
        dispatcher.register("capability.invoke", new CapabilityInvokeMethod(bus));
        dispatcher.register("capability.list", new CapabilityListMethod(bus));
        // 内置 host method
        dispatcher.register("session.connect", hostMethods::sessionConnect);
        dispatcher.register("session.disconnect", hostMethods::sessionDisconnect);
        dispatcher.register("session.list", hostMethods::sessionList);
        dispatcher.register("session.info", hostMethods::sessionInfo);
        dispatcher.register("command.run", hostMethods::commandRun);
        dispatcher.register("api.token", hostMethods::apiToken);
        dispatcher.register("api.methods", hostMethods::apiMethods);
    }

    public void start() throws IOException {
        if (!config.enabled()) return;
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
        httpServer.createContext("/rpc", new RpcHandler(config.token(), dispatcher));
        httpServer.setExecutor(null); // default executor
        httpServer.start();
        actualPort = httpServer.getAddress().getPort();
        log.info("External API listening on 127.0.0.1:{}", actualPort);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    public int port() { return actualPort; }
    public String token() { return config.token(); }
    public boolean enabled() { return config.enabled(); }
}
```

- [ ] **Step 4: Create `McpEndpoint` placeholder**

`api-server/src/main/java/com/jlshell/api/server/McpEndpoint.java`:

```java
package com.jlshell.api.server;

/**
 * MCP（Model Context Protocol）端点占位。
 *
 * 本次不实现。后续 MCP server（Streamable HTTP / stdio）应：
 *  1. 复用 ApiServer 的 MethodDispatcher 调度能力 method；
 *  2. 用 capability.list 生成 MCP tools 清单（name=pluginId.capability，inputSchema 透传）；
 *  3. 把 MCP tool call 映射成 capability.invoke。
 * 留作独立 spec。
 */
public final class McpEndpoint {
    private McpEndpoint() {}
}
```

- [ ] **Step 5: Write `ApiServerTest` (real HTTP round-trip)**

`api-server/src/test/java/com/jlshell/api/server/ApiServerTest.java`:

```java
package com.jlshell.api.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.api.server.dispatch.HostMethods;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiServerTest {

    private ApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    private HostMethods stubHost() {
        return new HostMethods() {
            @Override public CompletableFuture<JsonElement> sessionConnect(JsonElement p) { return CompletableFuture.completedFuture(new JsonPrimitive("sid-1")); }
            @Override public CompletableFuture<JsonElement> sessionDisconnect(JsonElement p) { return CompletableFuture.completedFuture(JsonNull.INSTANCE); }
            @Override public CompletableFuture<JsonElement> sessionList(JsonElement p) { return CompletableFuture.completedFuture(new com.google.gson.JsonArray()); }
            @Override public CompletableFuture<JsonElement> sessionInfo(JsonElement p) { return CompletableFuture.completedFuture(new JsonObject()); }
            @Override public CompletableFuture<JsonElement> commandRun(JsonElement p) { return CompletableFuture.completedFuture(new JsonObject()); }
            @Override public CompletableFuture<JsonElement> apiToken(JsonElement p) { return CompletableFuture.completedFuture(new JsonPrimitive("tok")); }
            @Override public CompletableFuture<JsonElement> apiMethods(JsonElement p) { return CompletableFuture.completedFuture(new com.google.gson.JsonArray()); }
        };
    }

    private CapabilityBus stubBus() {
        return new CapabilityBus() {
            @Override public CompletableFuture<RpcResponse> invoke(RpcRequest r) {
                return CompletableFuture.completedFuture(RpcResponse.ok(new JsonPrimitive("echoed")));
            }
            @Override public java.util.List<CapabilitySpec> listCapabilities(String sid) { return java.util.List.of(); }
        };
    }

    private void startServer() throws Exception {
        server = new ApiServer(new ApiServerConfig(0, "secret-token", true), stubBus(), stubHost());
        server.start();
    }

    @AfterEach void tearDown() { if (server != null) server.stop(); }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/rpc"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void authorizedRequestReturnsResult() throws Exception {
        startServer();
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0"); body.addProperty("id", 1); body.addProperty("method", "session.connect");
        body.add("params", new JsonObject());
        HttpResponse<String> r = post("secret-token", body.toString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"result\":\"sid-1\"");
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        startServer();
        HttpResponse<String> r = post("wrong", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session.list\"}");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        startServer();
        HttpResponse<String> r = post("secret-token", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}");
        assertThat(r.body()).contains("-32601");
    }

    @Test
    void capabilityInvokePassesThrough() throws Exception {
        startServer();
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "s1"); params.addProperty("pluginId", "com.a");
        params.addProperty("capability", "echo"); params.add("args", new JsonPrimitive("hi"));
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0"); body.addProperty("id", 3); body.addProperty("method", "capability.invoke");
        body.add("params", params);
        HttpResponse<String> r = post("secret-token", body.toString());
        assertThat(r.body()).contains("\"result\":\"echoed\"");
    }
}
```

- [ ] **Step 6: Run ApiServer test**

Run: `mvn test -pl api-server -Dtest=ApiServerTest`
Expected: PASS, "Tests run: 4". If 401 test fails because default executor + async write race, ensure `RpcHandler.write` calls `exchange.close()` and the 401 path is synchronous (it is). If timing flakiness, the `whenComplete` async path may need `exchange` sent before the handler thread returns — `com.sun.net.httpserver` tolerates deferred send. Verify.

- [ ] **Step 7: Install + commit**

```bash
mvn install -pl api-server -DskipTests -q
git add api-server/
git commit -m "feat(api-server): ApiServer HTTP JSON-RPC + capability pass-through + token enforcement"
```

---

## Task 8: `ApiTokenStore`

**Files:**
- Create: `api-server/src/main/java/com/jlshell/api/server/ApiTokenStore.java`
- Test: `api-server/src/test/java/com/jlshell/api/server/ApiTokenStoreTest.java`

**Interfaces:**
- Produces: `ApiTokenStore.loadOrCreate()` → `String` (base64 of 32 random bytes); uses `~/.jlshell/api.token`; POSIX chmod 600; Windows ACL owner-only (best-effort). Idempotent: returns existing token if file present.

- [ ] **Step 1: Write failing test**

`ApiTokenStoreTest.java` (use a temp dir via system property override; `ApiTokenStore` reads `~/.jlshell/api.token` but allow override via `jlshell.home` system prop for testing):

```java
package com.jlshell.api.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenStoreTest {
    @Test
    void loadOrCreateIsIdempotent(@TempDir Path tmp) throws Exception {
        System.setProperty("jlshell.home", tmp.toString());
        String t1 = ApiTokenStore.loadOrCreate();
        String t2 = ApiTokenStore.loadOrCreate();
        assertThat(t1).isNotBlank();
        assertThat(t1).isEqualTo(t2); // 第二次读回同一个
    }
}
```

- [ ] **Step 2: Implement `ApiTokenStore`**

`api-server/src/main/java/com/jlshell/api/server/ApiTokenStore.java`:

```java
package com.jlshell.api.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ~/.jlshell/api.token 的读写。POSIX chmod 600；Windows 走默认（当前用户）。 */
public final class ApiTokenStore {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenStore.class);

    private ApiTokenStore() {}

    public static String loadOrCreate() {
        Path home = resolveHome();
        Path file = home.resolve("api.token");
        try {
            Files.createDirectories(home);
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String token = Base64.getEncoder().encodeToString(bytes);
            if (isPosix(home)) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.writeString(file, token, java.nio.file.StandardOpenOption.CREATE_NEW,
                        PosixFilePermissions.asFileAttribute(perms));
            } else {
                Files.writeString(file, token, java.nio.file.StandardOpenOption.CREATE_NEW);
                // Windows: best-effort 限制到当前用户（posixAttribute 不可用，留作后续）
            }
            log.info("Created API token at {}", file);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load/create API token at " + file, e);
        }
    }

    private static Path resolveHome() {
        String override = System.getProperty("jlshell.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".jlshell");
    }

    private static boolean isPosix(Path p) {
        try { return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Exception e) { return false; }
    }
}
```

- [ ] **Step 3: Run test**

Run: `mvn test -pl api-server -Dtest=ApiTokenStoreTest`
Expected: PASS, "Tests run: 1".

- [ ] **Step 4: Commit**

```bash
git add api-server/src/main/java/com/jlshell/api/server/ApiTokenStore.java api-server/src/test/java/com/jlshell/api/server/ApiTokenStoreTest.java
git commit -m "feat(api-server): ApiTokenStore for ~/.jlshell/api.token (mode 600)"
```

---

## Task 9: `HostMethodsImpl` in `app` (session.*/command.run/api.*)

**Files:**
- Create: `app/src/main/java/com/jlshell/app/api/HostMethodsImpl.java`
- Test: `app/src/test/java/com/jlshell/app/api/HostMethodsImplTest.java`

**Interfaces:**
- Consumes: `HostMethods` (Task 6); `ConnectionProfileService.toConnectionRequest(String)`; `SessionManager.openSession/getSession/listSessions/closeSession`; `SessionId`; `CommandRequest`; `SshSession.execute`; `AppSettingsService` (for api.token); `MethodDispatcher` (for api.methods — or a static list). Gson.
- Produces: `HostMethodsImpl(ConnectionProfileService, SessionManager, SessionRegistry, AppSettingsService, Executor, String token)` implementing `HostMethods`.

- [ ] **Step 1: Add api-server + test deps to app pom**

In `app/pom.xml` `<dependencies>`, add:
```xml
        <dependency>
            <groupId>com.jlshell</groupId>
            <artifactId>api-server</artifactId>
            <version>${project.version}</version>
        </dependency>
```
And test deps (junit-jupiter, mockito-junit-jupiter, assertj-core, scope test).

- [ ] **Step 2: Write failing test for `session.connect` + `command.run` with mocks**

`app/src/test/java/com/jlshell/app/api/HostMethodsImplTest.java`:

```java
package com.jlshell.app.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.ConnectionProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostMethodsImplTest {

    @Mock ConnectionProfileService profileService;
    @Mock SessionManager sessionManager;
    @Mock SshSession sshSession;

    @Test
    void sessionConnectReturnsSessionId() throws Exception {
        ConnectionRequest req = new ConnectionRequest("n",
                new ConnectionTarget("h", 22, "u", Duration.ofSeconds(10), Duration.ofSeconds(30)),
                com.jlshell.core.model.AuthenticationMethod.PASSWORD,
                com.jlshell.core.security.CredentialPayload.forPassword("p".toCharArray()),
                com.jlshell.core.model.HostKeyVerificationMode.STRICT);
        when(profileService.toConnectionRequest("c1")).thenReturn(req);
        SessionId sid = SessionId.randomId();
        when(sshSession.sessionId()).thenReturn(sid);
        when(sessionManager.openSession(req)).thenReturn(CompletableFuture.completedFuture(sshSession));

        HostMethodsImpl host = new HostMethodsImpl(profileService, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject(); params.addProperty("connectionId", "c1");
        JsonObject out = host.sessionConnect(params).get().getAsJsonObject();
        assertThat(out.get("sessionId").getAsString()).isEqualTo(sid.toString());
    }

    @Test
    void commandRunExecutesViaSession() throws Exception {
        SessionId sid = SessionId.randomId();
        when(sshSession.sessionId()).thenReturn(sid);
        when(sshSession.execute(any(CommandRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new CommandResult("ls", 0, "out", "err", Duration.ZERO)));
        when(sessionManager.getSession(sid)).thenReturn(Optional.of(sshSession));

        HostMethodsImpl host = new HostMethodsImpl(profileService, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sid.toString());
        params.addProperty("command", "ls");
        JsonObject out = host.commandRun(params).get().getAsJsonObject();
        assertThat(out.get("stdout").getAsString()).isEqualTo("out");
        assertThat(out.get("exitCode").getAsInt()).isEqualTo(0);
    }
}
```

Note: `CredentialPayload.forPassword` — confirm signature in `core/.../security/CredentialPayload.java` (read it if unsure). `AuthenticationMethod.PASSWORD` and `HostKeyVerificationMode.STRICT` are enums in core.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=HostMethodsImplTest`
Expected: FAIL — `HostMethodsImpl` doesn't exist.

- [ ] **Step 4: Implement `HostMethodsImpl`**

`app/src/main/java/com/jlshell/app/api/HostMethodsImpl.java`:

```java
package com.jlshell.app.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.api.server.dispatch.HostMethods;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.ConnectionProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 内置 host method 实现：直接调 core 服务。 */
public class HostMethodsImpl implements HostMethods {
    private static final Logger log = LoggerFactory.getLogger(HostMethodsImpl.class);
    private final ConnectionProfileService profileService;
    private final SessionManager sessionManager;
    private final Executor executor;
    private final String token;

    public HostMethodsImpl(ConnectionProfileService profileService, SessionManager sessionManager,
                           Executor executor, String token) {
        this.profileService = profileService;
        this.sessionManager = sessionManager;
        this.executor = executor;
        this.token = token;
    }

    @Override
    public CompletableFuture<JsonElement> sessionConnect(JsonElement params) {
        String connectionId = str(params, "connectionId");
        if (connectionId == null) return fail("missing param: connectionId");
        return CompletableFuture.supplyAsync(() -> {
            ConnectionRequest req = profileService.toConnectionRequest(connectionId);
            return sessionManager.openSession(req).thenApply(s -> {
                JsonObject o = new JsonObject();
                o.addProperty("sessionId", s.sessionId().toString());
                return (JsonElement) o;
            });
        }, executor).thenCompose(x -> x);
    }

    @Override
    public CompletableFuture<JsonElement> sessionDisconnect(JsonElement params) {
        String sid = str(params, "sessionId");
        if (sid == null) return fail("missing param: sessionId");
        return sessionManager.closeSession(toSessionId(sid)).thenApply(v -> JsonNull.INSTANCE);
    }

    @Override
    public CompletableFuture<JsonElement> sessionList(JsonElement params) {
        return CompletableFuture.supplyAsync(() -> {
            JsonArray arr = new JsonArray();
            for (SessionDescriptor d : sessionManager.listSessions()) {
                JsonObject o = new JsonObject();
                o.addProperty("sessionId", d.sessionId().toString());
                o.addProperty("displayName", d.displayName());
                o.addProperty("host", d.target().host());
                o.addProperty("user", d.target().username());
                o.addProperty("state", d.state().name());
                arr.add(o);
            }
            return arr;
        }, executor);
    }

    @Override
    public CompletableFuture<JsonElement> sessionInfo(JsonElement params) {
        String sid = str(params, "sessionId");
        if (sid == null) return fail("missing param: sessionId");
        Optional<SshSession> found = sessionManager.getSession(toSessionId(sid));
        if (found.isEmpty()) return fail("session not found: " + sid);
        SshSession s = found.get();
        JsonObject o = new JsonObject();
        o.addProperty("sessionId", s.sessionId().toString());
        o.addProperty("displayName", s.displayName());
        o.addProperty("host", s.target().host());
        o.addProperty("port", s.target().port());
        o.addProperty("user", s.target().username());
        return CompletableFuture.completedFuture(o);
    }

    @Override
    public CompletableFuture<JsonElement> commandRun(JsonElement params) {
        String sid = str(params, "sessionId");
        String command = str(params, "command");
        if (sid == null || command == null) return fail("missing param: sessionId or command");
        int timeoutSec = params.isJsonObject() && params.getAsJsonObject().has("timeoutSec")
                ? params.getAsJsonObject().get("timeoutSec").getAsInt() : 30;
        Optional<SshSession> found = sessionManager.getSession(toSessionId(sid));
        if (found.isEmpty()) return fail("session not found: " + sid);
        CommandRequest req = new CommandRequest(command, Duration.ofSeconds(timeoutSec), false, null);
        return found.get().execute(req).thenApply(r -> {
            JsonObject o = new JsonObject();
            o.addProperty("stdout", r.stdout());
            o.addProperty("stderr", r.stderr());
            o.addProperty("exitCode", r.exitCode() == null ? -1 : r.exitCode());
            return (JsonElement) o;
        });
    }

    @Override
    public CompletableFuture<JsonElement> apiToken(JsonElement params) {
        return CompletableFuture.completedFuture(new JsonPrimitive(token));
    }

    @Override
    public CompletableFuture<JsonElement> apiMethods(JsonElement params) {
        JsonArray arr = new JsonArray();
        for (String m : List.of("session.connect", "session.disconnect", "session.list",
                "session.info", "command.run", "capability.invoke", "capability.list",
                "api.token", "api.methods")) {
            arr.add(new JsonPrimitive(m));
        }
        return CompletableFuture.completedFuture(arr);
    }

    private static String str(JsonElement e, String key) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static SessionId toSessionId(String s) { return new SessionId(UUID.fromString(s)); }

    private static CompletableFuture<JsonElement> fail(String msg) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(msg));
    }
}
```

Note: the impl ctor is `(ConnectionProfileService, SessionManager, Executor, String token)` (4 args). The test in Step 2 already uses this 4-arg form: `new HostMethodsImpl(profileService, sessionManager, Runnable::run, "tok")` (`Runnable::run` is a valid `Executor`).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=HostMethodsImplTest`
Expected: PASS, "Tests run: 2".

- [ ] **Step 6: Commit**

```bash
git add app/pom.xml app/src/main/java/com/jlshell/app/api/HostMethodsImpl.java app/src/test/java/com/jlshell/app/api/HostMethodsImplTest.java
git commit -m "feat(app): HostMethodsImpl — session.*/command.run/api.* over core services"
```

---

## Task 10: Wire `ApiServer` into `AppContext`

**Files:**
- Modify: `app/src/main/java/com/jlshell/app/AppContext.java`

**Interfaces:**
- Consumes: `ApiServer`, `ApiServerConfig`, `ApiTokenStore`, `CapabilityBusImpl`, `HostMethodsImpl` (Tasks 4, 7, 8, 9); `AppSettingsService` (existing)
- Produces: `AppContext` constructs `capabilityBus` + `hostMethods` + `apiServer`, starts it if enabled, stops on shutdown, passes `apiServer` to `MainWindow`.

- [ ] **Step 1: Read `AppContext` shutdown handling**

Read `/Users/edgarliu/Workspaces/JavaProjects/JLShell/app/src/main/java/com/jlshell/app/AppContext.java` fully (it's ~160 lines) to find the shutdown hook / close method and the `MainWindow` construction (around line 140). Identify where to insert api wiring (after `pluginManager`, before/around `MainWindow`) and where shutdown cleanup lives.

- [ ] **Step 2: Insert API wiring after `pluginManager`**

After the `PluginManager pluginManager = new PluginManager();` line (line 120), before "6.5 Vault", insert:

```java
        // 6b. RPC 内核 + 外部 API
        CapabilityBusImpl capabilityBus = new CapabilityBusImpl(pluginManager);
        boolean apiEnabled = "true".equalsIgnoreCase(appSettingsService.get("api.enabled", "false"));
        int apiPort = parsePortOrDefault(appSettingsService.get("api.port", "0"), 0);
        String apiToken;
        try {
            apiToken = apiEnabled ? com.jlshell.api.server.ApiTokenStore.loadOrCreate() : "";
        } catch (Exception e) {
            log.warn("Failed to init API token (non-fatal): {}", e.getMessage());
            apiToken = "";
        }
        com.jlshell.api.server.ApiServerConfig apiCfg =
                new com.jlshell.api.server.ApiServerConfig(apiPort, apiToken, apiEnabled);
```

Add helper at bottom of AppContext:
```java
    private static int parsePortOrDefault(String s, int def) {
        try { int p = Integer.parseInt(s); return (p >= 0 && p <= 65535) ? p : def; }
        catch (NumberFormatException e) { return def; }
    }
```

- [ ] **Step 3: Construct `HostMethodsImpl` + `ApiServer` after `connectionProfileService` is built**

`HostMethodsImpl` needs `connectionProfileService` (built at line 134). So insert after `connectionProfileService` construction (after line 135), before `MainWindow`:

```java
        com.jlshell.app.api.HostMethodsImpl hostMethods = new com.jlshell.app.api.HostMethodsImpl(
                connectionProfileService, sessionManager, executor, apiToken);
        com.jlshell.api.server.ApiServer apiServer =
                new com.jlshell.api.server.ApiServer(apiCfg, capabilityBus, hostMethods);
        if (apiEnabled) {
            try {
                apiServer.start();
                log.info("External API on 127.0.0.1:{} (token at ~/.jlshell/api.token)", apiServer.port());
            } catch (java.io.IOException e) {
                log.warn("API server failed to start (non-fatal): {}", e.getMessage());
            }
        }
```

- [ ] **Step 4: Pass `apiServer` to `MainWindow`**

Update the `MainWindow` constructor call (line 140) to pass `apiServer` as a new trailing arg. This requires `MainWindow` ctor to accept it — done in Task 11. For this task, just pass it; Task 11 updates the ctor + stores it. Add `apiServer` after `pluginManager`:
```java
                5,
                pluginManager,
                apiServer
        );
```

- [ ] **Step 5: Add shutdown stop**

Find AppContext's shutdown/cleanup (look for a `close()`/`stop()`/`shutdown()` method or a runtime shutdown hook). Add `apiServer.stop();` there. If none exists, add a shutdown hook:
```java
        Runtime.getRuntime().addShutdownHook(new Thread(apiServer::stop, "jlshell-api-shutdown"));
```
Insert this right after the `apiServer` is constructed (in the block from Step 3, unconditionally — stop() is safe when not started).

- [ ] **Step 6: Compile (expect MainWindow ctor mismatch until Task 11)**

Run: `mvn install -pl app -DskipTests -q`
Expected: BUILD FAILURE on `MainWindow` ctor (Task 11 fixes it). Do NOT commit until Task 11 lands; or do Task 11 first then compile. **Order:** implement Task 11's ctor change in the same commit batch. Proceed to Task 11, then run the combined compile.

- [ ] **Step 7: Commit (after Task 11 compiles)**

Defer commit to end of Task 11.

---

## Task 11: `MainWindow` accepts `apiServer`; `PreferencesDialog` API tab

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/MainWindow.java`
- Modify: `ui/src/main/java/com/jlshell/ui/dialog/PreferencesDialog.java`
- Modify: `ui/src/main/resources/i18n/messages.properties`
- Modify: `ui/src/main/resources/i18n/messages_zh_CN.properties`

**Interfaces:**
- Consumes: `ApiServer` (port(), token(), enabled()) from Task 7; `AppSettingsService`
- Produces: `MainWindow` ctor gains `ApiServer apiServer` (last arg); stores it; passes to `PreferencesDialog.show`. `PreferencesDialog.show` gains `ApiServer apiServer` arg; new "API" tab with enable checkbox + port field + current-status label + copy-token button; `applyPendingSettings` persists `api.enabled`/`api.port` and triggers restart prompt on change.

- [ ] **Step 1: Update `MainWindow` ctor to accept + store `apiServer`**

In `MainWindow.java`: add import `com.jlshell.api.server.ApiServer;`; add field `private final ApiServer apiServer;`; add ctor param `ApiServer apiServer` (last); assign it. Update the call in `AppContext` (Task 10 Step 4 already passes it).

- [ ] **Step 2: Update `PreferencesDialog.show` signature**

Change `show(...)` to add `ApiServer apiServer` after `String activeProjectId`. Update the call site in `MainWindow` (find `PreferencesDialog.show(` in MainWindow and add `apiServer`). Add `pendingApiEnabled[0]` (String "true"/"false") and `pendingApiPort[0]` (String) init:
```java
        String[] pendingApiEnabled = { appSettings.get("api.enabled", "false") };
        String[] pendingApiPort = { appSettings.get("api.port", "0") };
```

- [ ] **Step 3: Add "API" tab to `buildTabPane`**

Pass `apiServer`, `pendingApiEnabled`, `pendingApiPort` into `buildTabPane` and add:
```java
        Tab apiTab = new Tab(i18n.get("preferences.tab.api"));
        apiTab.setContent(buildApiPane(appSettings, i18n, apiServer, pendingApiEnabled, pendingApiPort));
        tabPane.getTabs().add(apiTab);  // 插在 about 之前
```

- [ ] **Step 4: Implement `buildApiPane`**

```java
    private static VBox buildApiPane(AppSettingsService appSettings, I18nService i18n,
                                     com.jlshell.api.server.ApiServer apiServer,
                                     String[] pendingApiEnabled, String[] pendingApiPort) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));

        CheckBox enableCb = new CheckBox(i18n.get("api.enabled"));
        enableCb.setSelected("true".equalsIgnoreCase(pendingApiEnabled[0]));
        enableCb.selectedProperty().addListener((o, ov, nv) -> pendingApiEnabled[0] = String.valueOf(nv));

        Label portLabel = new Label(i18n.get("api.port"));
        TextField portField = new TextField(pendingApiPort[0]);
        portField.setPromptText(i18n.get("api.port.hint"));
        portField.disableProperty().bind(enableCb.selectedProperty().not());
        portField.textProperty().addListener((o, ov, nv) -> pendingApiPort[0] = nv);

        String current = apiServer != null && apiServer.enabled()
                ? i18n.get("api.current", String.valueOf(apiServer.port()))
                : i18n.get("api.disabled", "");
        Label currentLabel = new Label(current);
        Label tokenHint = new Label(i18n.get("api.tokenHint"));

        Button copyToken = new Button(i18n.get("api.copyToken"));
        copyToken.setDisable(apiServer == null || apiServer.token() == null || apiServer.token().isEmpty());
        copyToken.setOnAction(e -> {
            String t = apiServer == null ? "" : apiServer.token();
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(t); cb.setContent(cc);
        });

        Label restart = new Label(i18n.get("api.restartRequired"));
        restart.setStyle("-fx-text-fill: gray;");

        box.getChildren().addAll(enableCb, new HBox(8, portLabel, portField),
                currentLabel, new HBox(8, tokenHint, copyToken), restart);
        return box;
    }
```

- [ ] **Step 5: Update `applyPendingSettings` to persist API settings + restart prompt**

Add `pendingApiEnabled`/`pendingApiPort` params to `applyPendingSettings`. Persist:
```java
        String prevApiEnabled = appSettings.get("api.enabled", "false");
        String prevApiPort = appSettings.get("api.port", "0");
        appSettings.set("api.enabled", pendingApiEnabled[0]);
        appSettings.set("api.port", pendingApiPort[0]);
        boolean apiChanged = !prevApiEnabled.equalsIgnoreCase(pendingApiEnabled[0])
                || !prevApiPort.equals(pendingApiPort[0]);
```
In `show()`'s OK result converter, after the existing language-restart check, add:
```java
                if (apiChanged) showRestartPrompt(owner, i18n);
```
(Reuse the same restart path that calls `System.exit(0)`.) Wire `apiChanged` by computing it in the result converter (the converter has access to the pending arrays). Move the `apiChanged` computation into the converter or make `applyPendingSettings` return a boolean. Simplest: compute in converter before calling apply — but apply writes. Keep apply void; compute `apiChanged` in converter from `appSettings` pre-values. Actually apply already reads prev values internally — extract: have `applyPendingSettings` return `boolean restartNeeded`. Change its return type to `boolean` and return `(!prevLang.equals(pendingLang[0])) || apiChanged`. Then the converter + apply button both use the return. Update both callers:
```java
        boolean needRestart = applyPendingSettings(...);
        // apply button: if (needRestart) showRestartPrompt(owner, i18n);
        // OK converter: same
```
This consolidates restart logic (language already triggered restart; now API too). Keep behavior: language change → restart (existing); API change → restart (new).

- [ ] **Step 6: Add i18n keys to both properties files**

`messages.properties`:
```
preferences.tab.api=API
api.enabled=Enable external API
api.port=Port
api.port.hint=0 = auto
api.current=Current: 127.0.0.1:{0}
api.disabled=External API is currently disabled
api.tokenHint=Token stored at ~/.jlshell/api.token
api.copyToken=Copy Token
api.restartRequired=API settings require restart to take effect.
```
`messages_zh_CN.properties`:
```
preferences.tab.api=API
api.enabled=启用外部 API
api.port=端口
api.port.hint=0 = 自动
api.current=当前：127.0.0.1:{0}
api.disabled=外部 API 当前已关闭
api.tokenHint=Token 存于 ~/.jlshell/api.token
api.copyToken=复制 Token
api.restartRequired=API 设置需重启后生效。
```

- [ ] **Step 7: Compile the whole project**

Run: `mvn install -DskipTests -q`
Expected: BUILD SUCCESS (AppContext + MainWindow + PreferencesDialog all aligned now).

- [ ] **Step 8: Commit (AppContext + MainWindow + PreferencesDialog + i18n together)**

```bash
git add app/src/main/java/com/jlshell/app/AppContext.java ui/ ui/src/main/resources/i18n/
git commit -m "feat: wire ApiServer in AppContext + Preferences API tab (enable/port/token, restart-on-change)"
```

---

## Task 12: Demo plugin registers a capability; packaging fix; final verification

**Files:**
- Modify: `plugins/plugin-demo/src/main/java/com/jlshell/demo/ScriptSnippetsPlugin.java`
- Modify: `build-dist.sh`

**Interfaces:**
- Consumes: `Capability`, `CapabilityContext`, `SshSessionContext.fileExplorer()` (Task 2/3)
- Produces: demo plugin registers `readConfig` capability (reads a remote file via SFTP, returns its String content as JSON).

- [ ] **Step 1: Register `readConfig` in `ScriptSnippetsPlugin.activate`**

In `ScriptSnippetsPlugin.java` `activate(PluginContext context)`, after the existing `openTab` block, add:
```java
        try {
            context.capabilityRegistry().register(
                com.jlshell.plugin.api.rpc.Capability.builder("readConfig")
                    .description("Read a remote file and return its text content.")
                    .requiresSession(true)
                    .handler((args, capCtx) -> {
                        String path = args != null && args.isJsonObject()
                                ? args.getAsJsonObject().get("path").getAsString() : null;
                        if (path == null || path.isBlank()) {
                            return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalArgumentException("path required"));
                        }
                        return capCtx.sshSession().orElseThrow().fileExplorer().readFile(path)
                                .thenApply(bytes -> {
                                    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                                    o.addProperty("path", path);
                                    o.addProperty("content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                    return (com.google.gson.JsonElement) o;
                                });
                    })
                    .build());
        } catch (Throwable t) {
            // 旧 host 无 capabilityRegistry（default no-op）— register 静默失败，不影响插件其余功能
        }
```
The `try/catch (Throwable)` guards against running this demo jar on an OLD host (no `capabilityRegistry` method → `AbstractMethodError`/`NoSuchMethodError`? No — `default` method on interface means old host's `PluginContext` lacks the default impl, so calling it throws `AbstractMethodError` at runtime on old hosts). Catch keeps the demo working on old hosts. On new host it registers fine.

Wait — `PluginContext` is in `plugin-api`. A demo jar compiled against NEW `plugin-api` has a `invokeinterface capabilityRegistry` ref. On OLD host (old plugin-api without that method), the JVM throws `NoSuchMethodError`/`AbstractMethodError`. The catch handles it. Good — this is the backward-compat guard for the demo specifically. Existing plugins compiled against OLD plugin-api never call it, so they're unaffected (they don't have the bytecode call).

- [ ] **Step 2: Packaging fix — add `jdk.httpserver` to jlink modules**

In `build-dist.sh` `detect_modules()`, add `jdk.httpserver` to the curated module list (insert after `java.net.http`):
```
java.net.http,jdk.httpserver,
```
The list is one `echo` line; splice `jdk.httpserver,` right after `java.net.http,`.

- [ ] **Step 3: Build the whole project**

Run: `mvn install -DskipTests -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run all tests**

Run: `mvn test`
Expected: all PASS across plugin-api, plugin-loader, api-server, app.

- [ ] **Step 5: Manual integration verification — run the app**

Run: `mvn javafx:run -pl app`
Then manually:
1. Open Preferences → API tab → enable + port 0 → Apply → restart prompt → OK (app exits). Restart `mvn javafx:run -pl app`.
2. On startup, log shows "External API on 127.0.0.1:<port>".
3. Read token from `~/.jlshell/api.token`.
4. From a terminal: `curl -s -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"api.methods"}' http://127.0.0.1:<port>/rpc` → returns method list.
5. `curl` `session.list` → `[]` (no sessions).
6. Create a saved SSH connection in the UI, copy its connectionId, `curl` `session.connect {"connectionId":"..."}` → returns `sessionId`.
7. `curl` `command.run {"sessionId":"...","command":"uname -a"}` → returns stdout.
8. Open the session's demo "Script Snippets" plugin tab (activates it, registers `readConfig`).
9. `curl` `capability.list {"sessionId":"<that session>"}` → includes `readConfig`.
10. `curl` `capability.invoke {"sessionId":"...","pluginId":"com.jlshell.demo.script-snippets","capability":"readConfig","args":{"path":"/etc/hostname"}}` → returns `{path, content}`.
11. Backward compat: confirm `plugin-sysmon` tab still opens, theme/locale switch still works, existing tabs unaffected.
12. Disable API in Preferences → restart → `curl` connection refused.

- [ ] **Step 6: Packaging regression (if JDK21_MAC set)**

Run: `./build-dist.sh --mac` (only if `JDK21_MAC` env var set; else skip and note it as "deferred — needs cross-platform JDK").
Verify: launch the produced `.app`, enable API, `curl` works (no `NoClassDefFoundError`).

- [ ] **Step 7: Commit**

```bash
git add plugins/plugin-demo/src/main/java/com/jlshell/demo/ScriptSnippetsPlugin.java build-dist.sh
git commit -m "feat(plugin-demo): register readConfig capability + packaging: add jdk.httpserver to jlink"
```

- [ ] **Step 8: Final commit (if any stray files)**

```bash
git status
# 若有遗漏则补提交
git add -A && git commit -m "chore: finalize plugin RPC + external API" || echo "nothing to commit"
```

---

## Self-Review notes (resolved during authoring)

- **Spec coverage:** capability registry (T2/T3), per-session routing (T4), inter-plugin invoke (T4 bus), external HTTP JSON-RPC (T6/T7), token (T8), host methods connect/command/list (T9), wiring (T10), prefs tab (T11), demo capability + packaging fix (T12). MCP deferred (McpEndpoint placeholder T7). All spec parts mapped.
- **Placeholders:** none — every code step has real code; `McpEndpoint` is an intentional documented stub (spec explicitly defers MCP).
- **Type consistency:** `HostMethodsImpl` ctor arity fixed to 4 args (test aligned). `CapabilityRegistryImpl.register(String, Capability)` vs interface `register(Capability)` reconciled via `PluginCapabilityRegistryView` (T4). `PluginManager.adoptContext`/`contextFor`/`registryFor`/`activateInstance` added consistently. `JsonRpcCodec` error constants match `MethodDispatcher.MethodNotFoundException.code` usage in `RpcHandler`.
