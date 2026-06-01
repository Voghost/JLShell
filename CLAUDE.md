# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build all modules
mvn clean package

# Run the application
mvn install -DskipTests -q && mvn javafx:run -pl app

# Run tests
mvn test

# Run a single test class
mvn test -pl core -Dtest=ConnectionRequestTest

# Build distributable package (current platform)
./build-dist.sh

# Build for specific platform
./build-dist.sh --mac
./build-dist.sh --win
./build-dist.sh --linux
./build-dist.sh --all
```

## Architecture

JLShell is a cross-platform SSH/SFTP client built with JavaFX. It uses a **layered architecture with dependency inversion** — the `core` module defines service interfaces, and implementation modules provide concrete classes discovered via Java `ServiceLoader`.

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
```

### Key Patterns

- **ServiceLoader-based DI**: `AppContext` (in `app` module) is the composition root. It discovers service implementations via `ServiceLoader` and wires them together. No Spring or DI framework.
- **SPI registrations**: Each implementation module registers its services in `META-INF/services/com.jlshell.*` files.
- **Plugin system**: Plugins implement `JlShellPlugin` from `plugin-api`, register via SPI, and are loaded at runtime from `~/.jlshell/plugins/` by `PluginManager` using `URLClassLoader` + `ServiceLoader`.

### Core Interfaces (in `core` module)

- `ConnectionManager` — SSH connection lifecycle
- `SessionManager` — session management
- `SftpService` — SFTP file operations
- `TerminalViewFactory` — terminal UI creation
- `CredentialStore` / `CredentialCipher` — secure credential storage (SQLite + AES-256-GCM)

### UI Structure

- `MainWindow` creates the primary JavaFX stage with tab-based workspace
- Each `SessionWorkspaceTab` combines a JediTerm terminal + SFTP browser for a connected session
- Themes (dark/light) and i18n (EN/ZH) are switchable at runtime

## Tech Stack

- **Java 21** + **JavaFX 21** (UI)
- **SSHJ** (SSH/SFTP client)
- **JediTerm** (terminal emulator)
- **JDBI 3** + **HikariCP** + **SQLite** (persistence)
- **Bouncy Castle** (AES-GCM encryption)
- **SLF4J + Logback** (logging)

## Distribution

The `build-dist.sh` script produces self-contained packages with a jlink'd JRE (~50 MB):
- **macOS**: `.app` bundle in a `.zip` (with `Info.plist`, `AppIcon.icns`, dock properties)
- **Linux**: `.tar.gz` with shell launcher + `.desktop` entry
- **Windows**: `.zip` with `.bat` and `.vbs` launchers (VBS for no-console-window mode)

Cross-platform builds require JDK 21 for each target platform (set via `JDK21_MAC`, `JDK21_WIN`, `JDK21_LINUX` env vars).
