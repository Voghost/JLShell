# JLShell

[中文文档](README_zh.md)

A modern cross-platform SSH/SFTP client built with JavaFX, featuring an IDE-inspired UI, SFTP file browser, plugin system with inter-plugin RPC, and an external JSON-RPC API.

![Java](https://img.shields.io/badge/Java-21-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)
![JDBI](https://img.shields.io/badge/JDBI-3-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Features

### Terminal
- Full terminal emulation powered by JediTerm, with split-pane support (vertical / horizontal)
- 305+ built-in color schemes (Dracula, Solarized, One Half, etc.) + custom scheme editor
- Font family, size, line spacing, and ligature controls
- Local shell sessions (zsh / bash / cmd.exe)

### SFTP Browser
- Dual-panel file manager with folder tree + file list for both local and remote
- Upload, download, rename, delete, create folder
- Follow terminal directory (silent, session-scoped OSC 7 shell integration)

### Connection Management
- Save and organize connections into **projects** and **folders** (nested, up to 5 levels)
- Host key verification modes (auto-trust / ask on first connect / strict)
- **Vault** — encrypted credential storage (AES-256-GCM) with optional master password
- **Import** — bulk import from MobaXterm INI, Xshell .xsh, or manual JSON

### Plugin System
- Drop a JAR into `~/.jlshell/plugins/` to extend the app
- Plugins get full SSH session access: command execution, SFTP, interactive sessions
- **Inter-plugin RPC** — plugins declare capabilities and invoke each other
- Includes Script Snippets demo plugin + System Monitor plugin

### External API (JSON-RPC)
- HTTP JSON-RPC 2.0 server on `127.0.0.1`, bearer token authentication
- Create connections, run commands, invoke plugin capabilities — all from external tools
- Foundation for future AI / MCP integration
- Enable/configure in **Preferences → API**

### Appearance
- Dark and Light themes, switchable at runtime
- English and Simplified Chinese, switchable in Preferences (restart required)

---

## Screenshots

> Coming soon

## Download

Pre-built packages with bundled JRE (~50 MB, no JDK required):

| Platform | Download | How to run |
|----------|----------|------------|
| macOS | `JLShell-x.x.x-mac.zip` | Unzip → double-click `JLShell.app` |
| Linux | `JLShell-x.x.x-linux.tar.gz` | Unzip → `./JLShell.sh` |
| Windows | `JLShell-x.x.x-win.zip` | Unzip → double-click `JLShell.vbs` |

→ [Latest Release](../../releases/latest)

## Build from Source

**Requirements:** JDK 21, Maven 3.9+

```bash
# Run locally (current platform)
mvn install -DskipTests -q && mvn javafx:run -pl app

# Build distributable package for current platform
./build-dist.sh

# Build for all platforms (requires JDK 21 for each target platform)
JDK21_LINUX=/path/to/linux-jdk21 \
JDK21_WIN=/path/to/win-jdk21 \
./build-dist.sh --all
```

Output is in `dist/`.

---

## Plugin Development

For the complete package contract, Maven configuration, validation checklist, and store publishing rules, see
[Plugin Development and Publishing Specification](plugins/README.md). Capability routing and the local JSON-RPC API
are documented in [Plugin and External API Guide](docs/plugin-and-external-api-guide.md), while the client-side store
protocol is documented in [Client Plugin Store API](docs/client-plugin-store-api.md).

### Quick Start

Implement `JlShellPlugin` from the `plugin-api` module:

```java
public class MyPlugin implements JlShellPlugin, PluginView {

    @Override public String id()          { return "com.example.my-plugin"; }
    @Override public String displayName() { return "My Plugin"; }
    @Override public String version()     { return "1.0.0"; }
    @Override public String author()      { return "Example Team"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
    @Override public String description() { return "Utilities for an SSH session."; }
    @Override public boolean requiresSshSession() { return true; }

    @Override
    public void activate(PluginContext ctx) { /* called when session opens */ }

    @Override
    public void deactivate() { /* cleanup */ }

    @Override
    public Node createView(PluginContext ctx) {
        Button btn = new Button("Run df -h");
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

Register via `META-INF/services/com.jlshell.plugin.api.JlShellPlugin`, build a fat JAR, and drop it into
`~/.jlshell/plugins/`. Program-level plugins implement `JlShellProgramPlugin`, use its corresponding ServiceLoader
file, and install under `~/.jlshell/program-plugins/`.

Every store-distributed JAR must contain these main manifest attributes:

```text
JLShell-Plugin-Id: com.example.my-plugin
JLShell-Plugin-Version: 1.0.0
JLShell-Plugin-Scope: SESSION
```

It must also contain a UTF-8 package descriptor at `META-INF/jlshell-plugin.json`:

```json
{
  "schemaVersion": 1,
  "id": "com.example.my-plugin",
  "version": "1.0.0",
  "scope": "SESSION",
  "entrypoint": "com.example.MyPlugin",
  "displayName": "My Plugin",
  "description": "Plugin description.",
  "author": "Example Team",
  "minHostVersion": "0.1.0"
}
```

The JSON descriptor, manifest ID/version/scope, runtime `id()`/`version()`, ServiceLoader implementation, and store
`pluginId`/`version`/`scope`/`entrypoint`/compatibility range must agree exactly. The client verifies the final JAR
size, lowercase SHA-256, approval status, descriptor, manifest, SPI declaration, and entrypoint class before installation.

See `plugins/plugin-demo/` for a complete session example and `plugins/plugin-program-demo/` for a program-level
example. Both inherit the standardized manifest and shade configuration from `plugins/pom.xml`. See
[`plugins/README.md`](plugins/README.md) for the authoring guide and
[`docs/plugin-package-spec.md`](docs/plugin-package-spec.md) for the complete package contract.

### Plugin Context API

`PluginContext` provides access to:

| API | Description |
|-----|-------------|
| `sshSession()` | Active SSH session (commands, SFTP, interactive shell) |
| `capabilityRegistry()` | Register capabilities for inter-plugin RPC |
| `capabilityBus()` | Invoke other plugins' capabilities (returns `null` on old hosts) |
| `storage()` | Persistent key-value store scoped to this plugin (returns `null` on old hosts) |
| `openTab()` / `closeTab()` | Manage plugin tab in workspace |
| `showNotification()` | Display in-app notifications |
| `themeNameProperty()` | Observable theme changes |
| `localeProperty()` | Observable locale changes |

### SSH Session Capabilities

`SshSessionContext` exposes:

| Capability | Description |
|------------|-------------|
| `commandExecutor()` | Execute one-shot shell commands |
| `interactiveCommandExecutor()` | Multi-step interactive sessions (sudo, 2FA) |
| `fileExplorer()` | SFTP operations (list, read, write, delete) |

### Plugin Storage

Plugins can persist data (settings, cache, user preferences) via `PluginStorage` — a simple key-value store backed by the app's SQLite database, with automatic namespace isolation per plugin. No SQLite/JDBI dependency needed in your plugin.

```java
PluginStorage store = ctx.storage();
if (store != null) {
    // Read & write
    store.put("lastDirectory", "/var/log");
    String dir = store.get("lastDirectory");              // "/var/log"
    String fallback = store.get("missing", "default");    // "default"

    // List, remove, clear
    Set<String> keys = store.keys();     // only this plugin's keys
    store.remove("lastDirectory");
    store.clear();                       // delete all data for this plugin
}
```

Data is stored in the `plugin_storage` table with `(plugin_id, key)` as the primary key — each plugin can only read/write its own data, and keys never collide across plugins. Data persists across app restarts.

> **Backward compatibility:** `ctx.storage()` returns `null` on older hosts. Always check for `null` before use, just like `capabilityBus()`.

### Inter-Plugin RPC

Plugins can declare **capabilities** that other plugins (or the external API) invoke:

```java
@Override
public void activate(PluginContext ctx) {
    ctx.capabilityRegistry().register(
        Capability.builder("readConfig")
            .description("Read a remote file and return its content.")
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

Capabilities are routed by `(sessionId, pluginId, capabilityName)` and share the same `CapabilityBus` as the external API — so external callers can invoke them directly.

### Invoking Other Plugins' Capabilities

Use `ctx.capabilityBus()` to call another plugin's capability from within your plugin:

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

### Real-World Example: Script Snippets ↔ System Monitor

The bundled demo plugins demonstrate the full RPC cycle:

1. **System Monitor** registers a `getMetrics` capability that collects CPU, memory, network, and disk metrics from the remote server via SSH
2. **Script Snippets** has a "📊 Fetch Metrics" button that calls `getMetrics` on System Monitor through the `CapabilityBus`
3. Both capabilities are also available to external callers via the HTTP API:

```bash
# Get metrics from System Monitor plugin
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"capability.invoke","params":{"sessionId":"...","pluginId":"com.jlshell.sysmon","capability":"getMetrics"}}' \
     http://127.0.0.1:$PORT/rpc

# Read a remote file via Script Snippets plugin
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"capability.invoke","params":{"sessionId":"...","pluginId":"com.jlshell.demo.script-snippets","capability":"readConfig","args":{"path":"/etc/hostname"}}}' \
     http://127.0.0.1:$PORT/rpc
```

---

## External API

JLShell exposes a JSON-RPC 2.0 server on `127.0.0.1`. Enable it in **Preferences → API**.

### Authentication

A bearer token is stored at `~/.jlshell/api.token` (file mode 600 on POSIX). Pass it in the `Authorization` header:

```
Authorization: Bearer <token>
```

### Available Methods

| Method | Description |
|--------|-------------|
| `session.connect` | Establish an SSH connection |
| `session.disconnect` | Close a session |
| `session.list` | List active sessions |
| `session.info` | Get session details |
| `command.run` | Execute a remote command |
| `capability.list` | List plugin capabilities for a session |
| `capability.invoke` | Invoke a plugin capability |
| `api.token` | Get current API token |
| `api.methods` | List available API methods |

### Example

```bash
# List active sessions
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"session.list"}' \
     http://127.0.0.1:$PORT/rpc

# Run a command
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"command.run","params":{"sessionId":"...","command":"uname -a"}}' \
     http://127.0.0.1:$PORT/rpc
```

For capability invocation examples, see [Inter-Plugin RPC → Real-World Example](#real-world-example-script-snippets--system-monitor) above.

---

## Connection Import

Import connections from other SSH clients or manual entry in **Preferences → Import**:

| Source | Format | Notes |
|--------|--------|-------|
| MobaXterm | `.ini` | Parses `[SessionSettings\...]` sections; folder hierarchy preserved |
| Xshell | `.xsh` | Single file or directory batch; GBK fallback for Chinese versions |
| Manual | JSON | `[{name, host, port, user, authType, ...}]`; also supports table row editing |

Passwords are not imported (fill in later via Edit Connection).

---

## Vault (Credential Management)

Encrypt and manage credentials in the built-in vault:

- **AES-256-GCM** encryption with 12-byte IV
- Two modes: system key (transparent) or user master password (PBKDF2, 310K iterations)
- Store passwords, private keys, and key content
- Project-scoped entries
- Lock / unlock at runtime

---

## Project Structure

```
jlshell-parent
├── app            — Application entry point, AppContext (manual DI), packaging
├── api-server     — External JSON-RPC API server (JDK HttpServer)
├── program-api    — Public SPI for host JSON-RPC methods (no core dependency)
├── core           — Shared domain models and interfaces
├── data           — JDBI DAOs, SQLite persistence, AES-256-GCM credential cipher
├── ssh            — SSHJ-based SSH session management
├── sftp           — SFTP file transfer service
├── terminal       — JediTerm integration, 305+ color schemes
├── ui             — JavaFX views, themes, i18n, connection import
├── plugin-api     — Public SPI for plugin developers (standalone publishable JAR)
├── plugin-loader  — Plugin discovery, lifecycle, per-session capability bus
└── plugins
    ├── plugin-program-demo — Program plugin, settings, global capabilities and JSON-RPC provider
    ├── plugin-session-demo — Session plugin, SSH access, storage and session capability
    ├── plugin-demo         — Script Snippets example (readConfig capability)
    └── plugin-sysmon       — System Monitor with real-time charts + getMetrics capability
```

## Tech Stack

- **Java 21** + **JavaFX 21** (UI)
- **SSHJ** (SSH/SFTP client)
- **JediTerm** (terminal emulator, Swing → SwingNode)
- **JDBI 3** + **HikariCP** + **SQLite** (persistence, WAL mode)
- **Bouncy Castle** (AES-256-GCM credential encryption)
- **Gson** (JSON / JSON-RPC codec)
- **OSHI** (system metrics, used by plugin-sysmon)
- **jlink** (self-contained JRE, ~50 MB)

## License

MIT
