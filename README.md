# JLShell

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
- Follow terminal directory (OSC 7 prompt hook injection)

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

### Quick Start

Implement `JlShellPlugin` from the `plugin-api` module:

```java
public class MyPlugin implements JlShellPlugin, PluginView {

    @Override public String id()          { return "com.example.my-plugin"; }
    @Override public String displayName() { return "My Plugin"; }
    @Override public String version()     { return "1.0.0"; }
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

Register via `META-INF/services/com.jlshell.plugin.api.JlShellPlugin`, build a fat JAR, and drop it into `~/.jlshell/plugins/`. The plugin appears in the **Plugins** tab on next launch.

See `plugins/plugin-demo/` for a complete working example.

### Plugin Context API

`PluginContext` provides access to:

| API | Description |
|-----|-------------|
| `sshSession()` | Active SSH session (commands, SFTP, interactive shell) |
| `capabilityRegistry()` | Register capabilities for inter-plugin RPC |
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

# Invoke a plugin capability
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":3,"method":"capability.invoke","params":{"sessionId":"...","pluginId":"com.jlshell.demo.script-snippets","capability":"readConfig","args":{"path":"/etc/hostname"}}}' \
     http://127.0.0.1:$PORT/rpc
```

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
├── core           — Shared domain models and interfaces
├── data           — JDBI DAOs, SQLite persistence, AES-256-GCM credential cipher
├── ssh            — SSHJ-based SSH session management
├── sftp           — SFTP file transfer service
├── terminal       — JediTerm integration, 305+ color schemes
├── ui             — JavaFX views, themes, i18n, connection import
├── plugin-api     — Public SPI for plugin developers (standalone publishable JAR)
├── plugin-loader  — Plugin discovery, lifecycle, per-session capability bus
└── plugins
    ├── plugin-demo    — Script Snippets example (with readConfig capability)
    └── plugin-sysmon  — System Monitor with real-time charts
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
