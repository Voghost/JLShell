# JLShell

A modern SSH client built with JavaFX and Spring Boot, featuring a clean IDE-inspired UI, SFTP file browser, and a plugin system.

![Java](https://img.shields.io/badge/Java-21-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Features

- **SSH Terminal** — Full terminal emulation with split-pane support (vertical / horizontal)
- **SFTP Browser** — Dual-panel file manager with folder tree + file list for both local and remote, upload/download/rename/delete
- **Connection Manager** — Save and organize connections into projects and folders (nested, up to N levels)
- **Plugin System** — Drop a JAR into `~/.jlshell/plugins/` to extend the app; includes a Script Snippets demo plugin
- **Themes** — Dark (IntelliJ-style) and Light themes, switchable at runtime
- **i18n** — English and Simplified Chinese, switchable in Preferences
- **Preferences** — Font family, size, line spacing, ligatures for the terminal

## Screenshots

> Coming soon

## Download

Pre-built packages with bundled JRE (no JDK required):

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

## Plugin Development

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
        // return your JavaFX UI
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

See `plugin-demo/` for a complete working example (8 predefined SSH command snippets).

## Project Structure

```
jlshell-parent
├── app            — JavaFX Application entry point, packaging
├── core           — Shared domain models and interfaces
├── data           — JPA repositories, SQLite persistence
├── ssh            — SSHJ-based SSH session management
├── sftp           — SFTP file transfer service
├── ui             — JavaFX views, themes, i18n
├── plugin-api     — Public SPI for plugin developers (standalone publishable JAR)
├── plugin-loader  — Spring @Service for plugin discovery and lifecycle
└── plugin-demo    — Example plugin: Script Snippets
```

## Tech Stack

- **Java 21** + **JavaFX 21**
- **Spring Boot 3** (DI, JPA, lifecycle)
- **SSHJ** (SSH/SFTP)
- **SQLite** + **Hibernate** (local storage)
- **JediTerm** (terminal emulator)
- **jlink** (bundled JRE, ~50 MB)

## License

MIT
