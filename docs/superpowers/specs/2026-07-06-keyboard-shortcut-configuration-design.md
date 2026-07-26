# Keyboard Shortcut Configuration Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a configurable keyboard shortcut system to JLShell's Preferences dialog, allowing users to customize both application-level and terminal-level shortcuts, with search, conflict detection, and one-click reset to defaults.

**Architecture:** A centralized `ShortcutRegistry` manages all shortcut definitions and user customizations using a unified string representation. A `ShortcutConverter` translates between this string format and platform-specific key representations (JavaFX `KeyCodeCombination` for app shortcuts, Swing `KeyStroke` for terminal shortcuts). The Preferences dialog gets a new "Shortcuts" tab with a searchable list and key-recording UI.

**Tech Stack:** JavaFX (UI), Swing KeyStroke (terminal), AppSettingsService (persistence), JediTerm SystemSettingsProvider (terminal integration)

---

## 1. Unified Shortcut String Representation

All shortcuts are stored as **canonical strings** with modifiers in fixed order separated by spaces, followed by the key:

```
meta C          → macOS: ⌘C, others: Ctrl+C
ctrl shift C    → macOS: ⌃⇧C, others: Ctrl+Shift+C
alt LEFT        → macOS: ⌥←, others: Alt+Left
shift PAGE_UP   → macOS: ⇧⇡, others: Shift+PageUp
```

**Modifier order:** `ctrl → alt → shift → meta`

**Modifier mapping:**
- `meta` = `SHORTCUT_DOWN` in JavaFX (Cmd on macOS, Ctrl on others)
- `ctrl` = `CONTROL_DOWN`
- `alt` = `ALT_DOWN`
- `shift` = `SHIFT_DOWN`

**Key names:** Use JavaFX `KeyCode.getName()` for letter keys (uppercase single char) and special keys (UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, INSERT, COMMA, etc.).

**Null/empty:** Means no shortcut assigned for that slot.

---

## 2. ShortcutDefinition

Each configurable shortcut is a `ShortcutDefinition`:

```java
public record ShortcutDefinition(
    String id,                // unique ID, e.g. "app.newConnection", "terminal.copy"
    String category,          // "app" or "terminal"
    String nameKey,           // i18n key, e.g. "shortcut.app.newConnection"
    String defaultPrimary,    // default primary shortcut, e.g. "meta N"; null = no default
    String defaultSecondary   // default secondary shortcut; null = no default
)
```

Definitions are immutable and hardcoded in `ShortcutRegistry`.

---

## 3. ShortcutRegistry

Central service managing all shortcut definitions and user customizations.

```java
public class ShortcutRegistry {
    public ShortcutRegistry(AppSettingsService settings);

    // All definitions (immutable list)
    List<ShortcutDefinition> definitions();
    List<ShortcutDefinition> definitionsByCategory(String category);

    // Effective shortcuts (user override ?? default)
    String getEffectivePrimary(String id);
    String getEffectiveSecondary(String id);

    // User customization (writes to AppSettingsService)
    void setUserPrimary(String id, String value);   // null = clear
    void setUserSecondary(String id, String value);  // null = clear

    // Reset
    void resetAll();  // clear all user customizations

    // Conflict detection
    // Returns list of definitions whose effective primary or secondary matches the given keystroke,
    // excluding the definition with the given id itself
    List<ShortcutDefinition> findConflicts(String id, String keystroke);
}
```

**Settings keys:** `shortcut.<id>.primary` and `shortcut.<id>.secondary`
- Example: `shortcut.app.newConnection.primary` = `"meta N"`
- Example: `shortcut.terminal.paste.secondary` = `"shift INSERT"`

---

## 4. Predefined Shortcuts

### Application-level (category = "app")

| ID | i18n Key | Default Primary | Default Secondary |
|---|---|---|---|
| app.newConnection | shortcut.app.newConnection | meta N | — |
| app.refreshConnections | shortcut.app.refreshConnections | meta R | — |
| app.quit | shortcut.app.quit | meta Q | — |
| app.toggleSidebar | shortcut.app.toggleSidebar | meta B | — |
| app.toggleTopBar | shortcut.app.toggleTopBar | meta T | — |
| app.focusMode | shortcut.app.focusMode | meta shift F | — |
| app.preferences | shortcut.app.preferences | meta COMMA | — |

### Terminal-level (category = "terminal")

| ID | i18n Key | Default Primary | Default Secondary |
|---|---|---|---|
| terminal.copy | shortcut.terminal.copy | meta C | — |
| terminal.paste | shortcut.terminal.paste | meta V | shift INSERT |
| terminal.clearBuffer | shortcut.terminal.clearBuffer | meta K | ctrl L |
| terminal.find | shortcut.terminal.find | meta F | — |
| terminal.selectAll | shortcut.terminal.selectAll | — | — |
| terminal.pageUp | shortcut.terminal.pageUp | shift PAGE_UP | — |
| terminal.pageDown | shortcut.terminal.pageDown | shift PAGE_DOWN | — |
| terminal.lineUp | shortcut.terminal.lineUp | meta UP | ctrl UP |
| terminal.lineDown | shortcut.terminal.lineDown | meta DOWN | ctrl DOWN |

> On macOS, `meta` = Cmd. On other platforms, `meta` = Ctrl. Terminal defaults match JediTerm's `DefaultSettingsProvider`.

---

## 5. ShortcutConverter

Utility class for converting between the canonical string format and platform-specific representations.

```java
public final class ShortcutConverter {
    // String → JavaFX
    public static KeyCodeCombination toKeyCodeCombination(String spec);

    // String → Swing KeyStroke
    public static KeyStroke toKeyStroke(String spec);

    // String → display text (macOS: ⌘⌃⌥⇧ symbols; others: Ctrl/Alt/Shift text)
    public static String toDisplayText(String spec);

    // JavaFX KeyEvent → canonical string (for key recording)
    public static String fromKeyEvent(javafx.scene.input.KeyEvent event);
}
```

**Display text format** reuses the existing symbol mapping from `RefreshableTerminalPanel.formatKeyStroke()`:
- macOS: ⌘ (meta), ⌃ (ctrl), ⌥ (alt), ⇧ (shift), special key symbols (↑↓←→⇡⇣⌫↩)
- Others: Ctrl, Alt, Shift, Meta + key name

**KeyStroke conversion:** `meta` on non-macOS maps to `ctrl` modifier in Swing `KeyStroke`. On macOS, `meta` maps to `meta` (Cmd) modifier.

---

## 6. UI — Shortcuts Tab in Preferences

### Tab placement

New tab `TAB_SHORTCUTS = 8` inserted before `TAB_ABOUT` (which becomes 9).

### Layout

```
┌──────────────────────────────────────────────────────────┐
│ [🔍 搜索快捷键...                                    ] │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ── 程序 ──                                              │
│  新建连接          [  ⌘N  ] [  —  ] [×]                 │
│  刷新连接          [  ⌘R  ] [  —  ] [×]                 │
│  退出              [  ⌘Q  ] [  —  ] [×]                 │
│  切换侧栏          [  ⌘B  ] [  —  ] [×]                 │
│  折叠顶栏          [  ⌘T  ] [  —  ] [×]                 │
│  聚焦模式          [⌘⇧F  ] [  —  ] [×]                 │
│  偏好设置          [  ⌘,  ] [  —  ] [×]                 │
│                                                          │
│  ── 终端 ──                                              │
│  复制              [  ⌘C  ] [  —  ] [×]                 │
│  粘贴              [  ⌘V  ] [⇧Ins ] [×]                 │
│  清空缓冲区        [  ⌘K  ] [ ⌃L  ] [×]                 │
│  查找              [  ⌘F  ] [  —  ] [×]                 │
│  全选              [  —  ] [  —  ] [×]                   │
│  上翻页            [⇧⇡  ] [  —  ] [×]                   │
│  下翻页            [⇧⇣  ] [  —  ] [×]                   │
│  上滚一行          [  ⌘↑ ] [ ⌃↑ ] [×]                   │
│  下滚一行          [  ⌘↓ ] [ ⌃↓ ] [×]                   │
│                                                          │
├──────────────────────────────────────────────────────────┤
│                              [ 恢复默认快捷键 ]          │
└──────────────────────────────────────────────────────────┘
```

### Components

**Search field:** `TextField` with prompt text from i18n key `shortcut.search.prompt`. Filters the list in real-time, matching against:
- The i18n display name of each shortcut
- The display text of primary and secondary shortcuts

**Shortcut list:** `VBox` containing category headers and shortcut rows. Using VBox instead of ListView for simpler row customization.

**Category header:** `Label` with style class `shortcut-category-header`, text from i18n key `shortcut.category.app` / `shortcut.category.terminal`.

**Shortcut row:** `HBox` containing:
- Name label (left-aligned, grows)
- Primary shortcut button (style class `shortcut-key-button`)
- Secondary shortcut button (style class `shortcut-key-button`)
- Clear button (× icon, style class `shortcut-clear-button`)

**Key button states:**
- Normal: shows display text (e.g. "⌘C") or "—" if unassigned
- Recording: shows i18n text `shortcut.pressKey` with pulsing border animation
- Conflict: red border + tooltip showing conflicting action name

**Reset button:** `Button` at bottom-right with i18n text `shortcut.resetDefaults`. On click, shows confirmation dialog, then calls `ShortcutRegistry.resetAll()` and refreshes the UI.

### Key Recording Flow

1. User clicks a key button → button enters recording mode
2. Button text changes to `shortcut.pressKey` ("按下快捷键...")
3. `KEY_PRESSED` event filter added to the button's scene
4. On key press:
   - Convert `KeyEvent` to canonical string via `ShortcutConverter.fromKeyEvent()`
   - If Escape pressed → cancel recording, restore original display
   - If only modifier pressed (no key) → wait for next key
   - If valid combination → check conflicts via `ShortcutRegistry.findConflicts()`
   - If conflict → show error tooltip on button, reject, stay in recording
   - If no conflict → save to Registry, update button display, exit recording
5. On focus lost → cancel recording

---

## 7. Integration — MainWindow

**Current:** `installApplicationShortcuts()` hardcodes `KeyCodeCombination` values.

**New:** `installApplicationShortcuts()` reads from `ShortcutRegistry`:
```java
private void installApplicationShortcut(Scene scene, String shortcutId, Runnable action) {
    String spec = shortcutRegistry.getEffectivePrimary(shortcutId);
    if (spec == null) return;
    KeyCodeCombination combo = ShortcutConverter.toKeyCodeCombination(spec);
    scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
        if (combo.match(e) && !isTerminalFocusOwner(scene)) {
            e.consume();
            action.run();
        }
    });
}
```

**Refresh:** `MainWindow.refreshShortcuts()` removes old event filters and re-installs from Registry. Called after shortcut changes are saved in Preferences.

Implementation approach: maintain a list of installed event filter `EventHandler<KeyEvent>` references so they can be removed and re-added.

---

## 8. Integration — Terminal (JlshellSettingsProvider)

**Current:** `JlshellSettingsProvider` extends `DefaultSettingsProvider`, only overrides `getPasteActionPresentation()`.

**New:** `JlshellSettingsProvider` receives `ShortcutRegistry` in constructor. Each `getXxxActionPresentation()` method reads from Registry:

```java
@Override
public TerminalActionPresentation getCopyActionPresentation() {
    return buildPresentation("terminal.copy", "Copy");
}

private TerminalActionPresentation buildPresentation(String id, String defaultName) {
    String primary = shortcutRegistry.getEffectivePrimary(id);
    String secondary = shortcutRegistry.getEffectiveSecondary(id);
    List<KeyStroke> strokes = new ArrayList<>();
    if (primary != null) strokes.add(ShortcutConverter.toKeyStroke(primary));
    if (secondary != null) strokes.add(ShortcutConverter.toKeyStroke(secondary));
    return new TerminalActionPresentation(defaultName, strokes);
}
```

**Refresh:** `TerminalActionPresentation` stores the `keyStrokes` list by reference (no defensive copy), and `TerminalAction` accesses it on every `matches()` call. The right-click menu is rebuilt from scratch on every open. Therefore, if we pass a **mutable `ArrayList`** into each `TerminalActionPresentation`, we can update the list in-place and changes take effect immediately — no explicit refresh mechanism needed.

`JlshellSettingsProvider` stores references to these mutable keyStroke lists. On `refreshActions()`, it clears each list and re-populates from `ShortcutRegistry`. This affects both keyboard handling (via `TerminalAction.matches()`) and the right-click menu (rebuilt on each open).

---

## 9. Lifecycle in AppContext

1. `ShortcutRegistry` created after `AppSettingsService` (it depends on settings)
2. `ShortcutRegistry` passed to `MainWindow` constructor
3. `ShortcutRegistry` passed to `JlshellSettingsProvider` constructor
4. No shutdown needed — Registry is stateless beyond AppSettingsService

---

## 10. i18n Keys

### English (messages.properties)

```properties
preferences.tab.shortcuts=Shortcuts
shortcut.search.prompt=Search shortcuts...
shortcut.category.app=Application
shortcut.category.terminal=Terminal
shortcut.pressKey=Press shortcut...
shortcut.resetDefaults=Reset to Defaults
shortcut.resetDefaults.confirm=Reset all shortcuts to their default values?
shortcut.conflict=This shortcut is already used by "{0}"
shortcut.unassigned=—

shortcut.app.newConnection=New Connection
shortcut.app.refreshConnections=Refresh Connections
shortcut.app.quit=Quit
shortcut.app.toggleSidebar=Toggle Sidebar
shortcut.app.toggleTopBar=Collapse Top Bar
shortcut.app.focusMode=Focus Mode
shortcut.app.preferences=Preferences

shortcut.terminal.copy=Copy
shortcut.terminal.paste=Paste
shortcut.terminal.clearBuffer=Clear Buffer
shortcut.terminal.find=Find
shortcut.terminal.selectAll=Select All
shortcut.terminal.pageUp=Page Up
shortcut.terminal.pageDown=Page Down
shortcut.terminal.lineUp=Line Up
shortcut.terminal.lineDown=Line Down
```

### Chinese (messages_zh_CN.properties)

```properties
preferences.tab.shortcuts=快捷键
shortcut.search.prompt=搜索快捷键...
shortcut.category.app=程序
shortcut.category.terminal=终端
shortcut.pressKey=按下快捷键...
shortcut.resetDefaults=恢复默认快捷键
shortcut.resetDefaults.confirm=恢复所有快捷键为默认值？
shortcut.conflict=此快捷键已被「{0}」使用
shortcut.unassigned=—

shortcut.app.newConnection=新建连接
shortcut.app.refreshConnections=刷新连接
shortcut.app.quit=退出
shortcut.app.toggleSidebar=切换侧栏
shortcut.app.toggleTopBar=折叠顶栏
shortcut.app.focusMode=聚焦模式
shortcut.app.preferences=偏好设置

shortcut.terminal.copy=复制
shortcut.terminal.paste=粘贴
shortcut.terminal.clearBuffer=清空缓冲区
shortcut.terminal.find=查找
shortcut.terminal.selectAll=全选
shortcut.terminal.pageUp=上翻页
shortcut.terminal.pageDown=下翻页
shortcut.terminal.lineUp=上滚一行
shortcut.terminal.lineDown=下滚一行
```

---

## 11. Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `core/.../shortcut/ShortcutDefinition.java` | Create | Record: id, category, nameKey, defaultPrimary, defaultSecondary |
| `core/.../shortcut/ShortcutRegistry.java` | Create | Central registry: definitions, user overrides, conflict detection |
| `core/.../shortcut/ShortcutConverter.java` | Create | String ↔ KeyCodeCombination/KeyStroke/display text conversion |
| `ui/.../dialog/PreferencesDialog.java` | Modify | Add TAB_SHORTCUTS, buildShortcutsPane() |
| `ui/.../view/MainWindow.java` | Modify | installApplicationShortcuts() reads from Registry, add refreshShortcuts() |
| `terminal/.../support/JlshellSettingsProvider.java` | Modify | Override all getXxxActionPresentation() from Registry |
| `app/.../AppContext.java` | Modify | Create ShortcutRegistry, pass to MainWindow and JlshellSettingsProvider |
| `ui/.../resources/i18n/messages.properties` | Modify | Add shortcut i18n keys |
| `ui/.../resources/i18n/messages_zh_CN.properties` | Modify | Add shortcut i18n keys (ZH) |
