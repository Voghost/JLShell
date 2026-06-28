# JLShell Plugin Development

JLShell supports two plugin scopes: program-level plugins and session-level plugins. Both are discovered through Java `ServiceLoader` and should declare version compatibility metadata.

## Program-Level Plugins

Program plugins implement `com.jlshell.plugin.api.JlShellProgramPlugin` and are loaded from `~/.jlshell/program-plugins/` after the application starts.

Current program-level capabilities exposed by `ProgramPluginContext`:

- `capabilityRegistry()`: register global capabilities. These are invoked with `sessionId=null`.
- `capabilityBus()`: call system, program-plugin, or session-plugin capabilities.
- `storage()`: private persistent key-value storage scoped to the plugin id.
- `themeName()` / `themeNameProperty()`: current application theme.
- `locale()` / `localeProperty()`: current application locale.
- `resolveI18n(...)`: resolve host i18n keys with a fallback.
- `showNotification(...)`: show host notifications.
- `settingsView(...)`: optional JavaFX settings node displayed in Preferences > Plugins.
- `debug/info/warn/error`: host-provided plugin logging.

Program plugins do not receive `SshSessionContext` and must not assume an active SSH connection.

Service file:

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin
```

Demo module: `plugin-program-demo`. It registers global `hostInfo` and `echo` capabilities and exposes a small settings view.

## Session-Level Plugins

Session plugins implement `com.jlshell.plugin.api.JlShellPlugin` and are loaded from `~/.jlshell/plugins/`. They are activated inside a workspace session.

Session plugins can use:

- `sshSession()`: SSH command execution and remote file access when available.
- `openTab(...)` / `closeTab()` / `updateTabTitle(...)`: session workspace UI.
- `capabilityRegistry()`: register session capabilities.
- `capabilityBus()`: call other plugin capabilities.
- `storage()`: private persistent key-value storage scoped to the plugin id.
- theme, locale, i18n, notification, and logging helpers.

Service file:

```text
src/main/resources/META-INF/services/com.jlshell.plugin.api.JlShellPlugin
```

Demo module: `plugin-session-demo`. It opens a session tab, runs simple SSH commands, tests plugin storage, and registers `readTextFile`.

## Required Metadata

Every new plugin should implement:

```java
@Override public String id() { return "com.example.my-plugin"; }
@Override public String displayName() { return "My Plugin"; }
@Override public String version() { return "0.1.0"; }
@Override public String author() { return "Your Name"; }
@Override public String minHostVersionInclusive() { return "0.1.0"; }
@Override public String maxHostVersionInclusive() { return "0.1.999"; }
@Override public String description() { return "What this plugin does."; }
```

If `minHostVersionInclusive()` and `maxHostVersionInclusive()` are both blank, Preferences > Plugins shows an undeclared compatibility warning. If the current host version is outside the range, it shows an incompatible warning. Version comparison uses numeric semantic-version segments and ignores suffixes such as `RELEASE` and `SNAPSHOT`.

## Build And Install

Build all demo plugins:

```bash
mvn -f plugins/pom.xml clean package
```

Install demo plugins into the correct local folders:

```bash
./plugins/build-and-install.sh install
```

On Windows:

```bat
plugins\build-and-install.bat install
```

Program-level demo jars go to `~/.jlshell/program-plugins/`; session-level demo jars go to `~/.jlshell/plugins/`. Restart JLShell after installing external plugin jars.

## Capability Invocation

Program-level capability example:

```json
{
  "sessionId": null,
  "pluginId": "com.jlshell.demo.program-host-tools",
  "capability": "hostInfo",
  "args": null
}
```

Session-level capability example:

```json
{
  "sessionId": "<active-session-id>",
  "pluginId": "com.jlshell.demo.session-tools",
  "capability": "readTextFile",
  "args": { "path": "/etc/hosts" }
}
```
