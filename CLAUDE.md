# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build all modules
mvn clean package

# Run the application (must build first)
mvn install -DskipTests -q && mvn javafx:run -pl app

# Run tests
mvn test

# Run a single test class
mvn test -pl core -Dtest=ConnectionRequestTest

# Build distributable package (current platform)
./build-dist.sh

# Build for specific platform (requires platform JDK in env vars)
./build-dist.sh --mac
./build-dist.sh --win
./build-dist.sh --linux
./build-dist.sh --all
```

**Required JVM args** (set in build-dist.sh and run scripts): `--add-opens java.base/java.lang=ALL-UNNAMED` and `--add-opens java.desktop/sun.awt=ALL-UNNAMED`.

## Architecture

JLShell is a cross-platform SSH/SFTP client built with JavaFX. It uses **manual dependency injection** — `AppContext` (in `app` module) is the composition root that constructs all services explicitly in a fixed order. There is no Spring or DI framework.

### Module Dependency Flow

```
app  →  ui, core, data, ssh, sftp, terminal, plugin-loader
ui   →  core, plugin-api
ssh  →  core
sftp →  core
terminal → core
data →  core
plugin-loader → plugin-api, core
plugin-demo → plugin-api
plugin-sysmon → plugin-api
```

### AppContext Wiring Order (important — services depend on earlier ones)

1. **Database** — `DatabaseFactory.createDataSource(jdbcUrl)` → HikariCP → JDBI3 → `initSchema` runs `/schema.sql` idempotently
2. **Credential cipher** — `FileSystemMasterKeyProvider` loads/generates AES-256 key at `~/.jlshell/master.key`; `AesGcmCredentialCipher` wraps it
3. **Core services** — shared `ThreadPoolExecutor("jlshell-ssh", core=4, max=16, queue=256, CallerRunsPolicy)`, `InMemorySessionRegistry`, `PersistentFontProfileService`, `JdbiAppSettingsService`
4. **I18n + Theme** — created before SSH because `HostKeyConfirmationService` needs them for dialog text
5. **SSH/SFTP** — `SshjConnectionManager` → `DefaultSessionManager` → `SshjSftpService`
6. **Plugins** — `PluginManager` loads from classpath + `~/.jlshell/plugins/*.jar` + bundled `plugins/` dir
7. **UI** — `MainViewModel`, `JediTermTerminalViewFactory`, `ConnectionProfileService`, `MainWindow`

> **Important:** ServiceLoader is only used for plugin discovery. Core services are NOT discovered via ServiceLoader — they are wired explicitly in `AppContext`.

### Key Patterns

**Threading model (critical):**
- All blocking operations (DB, SSH, SFTP) run on the shared executor. The JavaFX thread is **never** blocked except for one case: host key confirmation dialogs.
- `FxThread.run(Runnable)` checks `Platform.isFxApplicationThread()` and runs inline if already on FX thread, otherwise calls `Platform.runLater()`.
- `FxThread.supplyAsync(Supplier)` returns a `CompletableFuture` that completes on the FX thread.
- Host key confirmation is the one place where a background thread blocks on the FX thread: `FxThread.supplyAsync(...).get(60, SECONDS)`.

**Plugin system:**
- Plugins implement `JlShellPlugin` from `plugin-api`, discovered via ServiceLoader.
- Lifecycle: `activate(context)` → `createView(context)` → `onTabSelected/Deselected()` / `onThemeChanged()` / `onLocaleChanged()` → `onSessionClosed()` → `deactivate()`.
- `DefaultPluginContext` has observable `writableThemeNameProperty` and `writableLocaleProperty` bound to `PluginManager`'s properties, so theme/locale changes propagate automatically to active plugins.
- Plugin API capabilities: `CommandExecutor`, `InteractiveCommandExecutor`, `FileExplorer` are functional. `LogViewer` and `ServerStatusProvider` are stubs that throw `UnsupportedOperationException`.

**No event bus:** Inter-component communication uses direct method calls and JavaFX observable properties. `MainWindow` listens to `ThemeService.currentThemeProperty()` and `I18nService.localeProperty()` and propagates changes to children.

**Credential security:** `CredentialPayload.clear()` zeroes the internal char array and is called in `SshjConnectionManager.connectBlocking()`'s finally block. If modifying the SSH connection flow, ensure `clear()` is still called.

**SFTP concurrency:** Every SFTP operation opens a fresh `SFTPClient` from the underlying `SSHClient` and closes it in try-with-resources. This is intentional — SSHJ's `SFTPClient` is not thread-safe for concurrent operations on the same channel.

### Core Interfaces (in `core` module)

- `ConnectionManager` — `CompletableFuture<SshSession> connect(ConnectionRequest)` (single method)
- `SessionManager` — session open/close + registry
- `SftpService` — all operations return `CompletableFuture`, take `SshSession` param (not host/port)
- `TerminalViewFactory` — terminal UI creation
- `CredentialStore` / `CredentialCipher` — secure credential storage (SQLite + AES-256-GCM)

### UI Structure

- `MainWindow` creates the JavaFX stage with a tab-based workspace
  - On Windows: `StageStyle.UNDECORATED` with `CustomTitleBar` + manual resize handler
  - On macOS/Linux: native menu bar via `MenuBar.setUseSystemMenuBar(true)`
- Each `SessionWorkspaceTab` has an inner `TabPane` with Terminal, Files, and Plugins tabs
- Files tab uses **lazy initialization** — `SftpBrowserPane` is only created when the tab is first selected
- Local shell sessions use `SwingNode` to embed JediTerm (a Swing component) inside JavaFX

### Theme & i18n

- `ThemeService` wraps `ObjectProperty<AppTheme>` — `DARK` and `LIGHT` each couple a CSS stylesheet with a terminal color scheme
- `I18nService` wraps `ResourceBundle` with `ObjectProperty<Locale>` — uses `ResourceBundle.Control.getNoFallbackControl()` to prevent locale fallback; returns the key itself on missing keys
- Locale files: `messages.properties` (EN) and `messages_zh_CN.properties` (ZH) in `ui/src/main/resources/i18n/`
- Switching locale/theme: `MainWindow.refreshAllTexts()` fully reconstructs the top area and sidebar (not incremental)

## Tech Stack

- **Java 21** + **JavaFX 21** (UI)
- **SSHJ** (SSH/SFTP client)
- **JediTerm** (terminal emulator; Swing-based, embedded via `SwingNode`)
- **JDBI 3** + **HikariCP** + **SQLite** (persistence — WAL mode, pool size 1)
- **Bouncy Castle** (AES-256-GCM credential encryption)
- **SLF4J + Logback** (logging)

## Database Notes

- SQLite with WAL mode (`PRAGMA journal_mode=WAL`) for concurrent read performance
- HikariCP pool size is 1 (SQLite single-writer limitation)
- All timestamps stored as INTEGER (Unix milliseconds)
- Entity IDs are UUIDs generated in `AbstractAuditableEntity.prepareInsert()`
- `AppSettingsService` is a key-value store in `app_settings` table (keys: `ui.language`, `ui.activeProject`, `terminal.font.family`, `terminal.font.size`, `ui.theme`)

## Distribution

The `build-dist.sh` script produces self-contained packages with a jlink'd JRE (~50 MB):
- **macOS**: `.app` bundle in a `.zip` (with `Info.plist`, `AppIcon.icns`, dock properties)
- **Linux**: `.tar.gz` with shell launcher + `.desktop` entry
- **Windows**: `.zip` with `.bat` and `.vbs` launchers (VBS for no-console-window mode); the `win-exe` Maven profile creates a Launch4j `.exe`

Cross-platform builds require JDK 21 for each target platform (set via `JDK21_MAC`, `JDK21_WIN`, `JDK21_LINUX` env vars).

## UI Styling Notes

- **Tab focus ring removal**: JavaFX tabs show a blue focus rectangle by default (from `-fx-focus-color`). To show only the bottom accent line on selected tabs, set `-fx-focus-color: transparent` and `-fx-faint-focus-color: transparent` on both `.tab-pane .tab` and `.tab-pane .tab:selected` in `dark-theme.css` and `light-theme.css`.

## Code Conventions

- Comments are often in Chinese (e.g., "手动依赖注入容器，替代 Spring IoC" in AppContext). Read them — they convey design intent.
- Connection folder depth is hardcoded to 5 (`maxFolderDepth` in AppContext).
- SPI registration files live in each module's `META-INF/services/com.jlshell.*`.