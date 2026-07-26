# Keyboard Shortcut Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable keyboard shortcut system to JLShell's Preferences dialog, allowing users to customize both application-level and terminal-level shortcuts, with search, conflict detection, and one-click reset to defaults.

**Architecture:** A centralized `ShortcutRegistry` in the `core` module manages all shortcut definitions and user customizations using a unified canonical string representation. `ShortcutConverter` translates between this string format and platform-specific key representations. The Preferences dialog gets a new "Shortcuts" tab with a searchable list and key-recording UI.

**Tech Stack:** JavaFX (UI), Swing KeyStroke (terminal), AppSettingsService (persistence), JediTerm SystemSettingsProvider (terminal integration)

## Global Constraints

- Java 21, JavaFX 21
- Canonical shortcut string format: modifiers in order `ctrl → alt → shift → meta`, space-separated, followed by key name (e.g. `"meta N"`, `"ctrl shift C"`)
- `meta` = `SHORTCUT_DOWN` in JavaFX (Cmd on macOS, Ctrl on others)
- All settings persisted via `AppSettingsService` with keys `shortcut.<id>.primary` and `shortcut.<id>.secondary`
- Terminal `TerminalActionPresentation` stores `keyStrokes` list by reference — use mutable `ArrayList` so in-place updates propagate
- No DI framework — `AppContext` wires everything manually
- i18n: keys in both `messages.properties` and `messages_zh_CN.properties`
- Comments can be in Chinese following codebase convention

---

### Task 1: ShortcutDefinition Record

**Files:**
- Create: `core/src/main/java/com/jlshell/core/shortcut/ShortcutDefinition.java`

**Interfaces:**
- Produces: `ShortcutDefinition` record with fields `id`, `category`, `nameKey`, `defaultPrimary`, `defaultSecondary`

- [ ] **Step 1: Create the ShortcutDefinition record**

```java
package com.jlshell.core.shortcut;

/**
 * 可配置快捷键的定义。
 * 每个定义包含唯一标识、分类、i18n 键和默认快捷键。
 */
public record ShortcutDefinition(
    String id,                // 唯一标识，如 "app.newConnection", "terminal.copy"
    String category,          // 分类："app" 或 "terminal"
    String nameKey,           // i18n key，如 "shortcut.app.newConnection"
    String defaultPrimary,    // 默认主快捷键，如 "meta N"；null 表示无默认
    String defaultSecondary   // 默认备用快捷键；null 表示无默认
) {}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/java/com/jlshell/core/shortcut/ShortcutDefinition.java
git commit -m "feat: add ShortcutDefinition record for keyboard shortcut configuration"
```

---

### Task 2: ShortcutConverter Utility

**Files:**
- Create: `core/src/main/java/com/jlshell/core/shortcut/ShortcutConverter.java`

**Interfaces:**
- Consumes: Canonical shortcut string format (modifiers in order `ctrl → alt → shift → meta`, space-separated, followed by key)
- Produces:
  - `ShortcutConverter.toKeyCodeCombination(String spec)` → `javafx.scene.input.KeyCodeCombination`
  - `ShortcutConverter.toKeyStroke(String spec)` → `javax.swing.KeyStroke`
  - `ShortcutConverter.toDisplayText(String spec)` → `String` (macOS: ⌘⌃⌥⇧ symbols; others: Ctrl/Alt/Shift text)
  - `ShortcutConverter.fromKeyEvent(javafx.scene.input.KeyEvent event)` → `String` canonical string

- [ ] **Step 1: Create ShortcutConverter with all conversion methods**

```java
package com.jlshell.core.shortcut;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快捷键规范字符串与平台特定表示之间的转换工具。
 * 规范格式：修饰键按 ctrl → alt → shift → meta 排列，空格分隔，最后是键名。
 * 例如："meta N"、"ctrl shift C"、"alt LEFT"、"shift PAGE_UP"
 */
public final class ShortcutConverter {

    private ShortcutConverter() {}

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // ── 规范字符串 → JavaFX KeyCodeCombination ──

    public static KeyCodeCombination toKeyCodeCombination(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.strip().split("\\s+");
        boolean ctrl = false, alt = false, shift = false, meta = false;
        String keyName = parts[parts.length - 1];
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].toLowerCase()) {
                case "ctrl" -> ctrl = true;
                case "alt" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> meta = true;
            }
        }
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (meta) modifiers.add(KeyCombination.SHORTCUT_DOWN);
        if (ctrl) modifiers.add(KeyCombination.CONTROL_DOWN);
        if (alt) modifiers.add(KeyCombination.ALT_DOWN);
        if (shift) modifiers.add(KeyCombination.SHIFT_DOWN);
        return new KeyCodeCombination(parseKeyCode(keyName),
                modifiers.toArray(new KeyCombination.Modifier[0]));
    }

    // ── 规范字符串 → Swing KeyStroke ──

    public static KeyStroke toKeyStroke(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.strip().split("\\s+");
        boolean ctrl = false, alt = false, shift = false, meta = false;
        String keyName = parts[parts.length - 1];
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].toLowerCase()) {
                case "ctrl" -> ctrl = true;
                case "alt" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> meta = true;
            }
        }
        int keyCode = parseSwingKeyCode(keyName);
        int modifiers = 0;
        // meta 在 macOS 上映射为 Cmd (META_DOWN_MASK)，其他平台映射为 Ctrl (CTRL_DOWN_MASK)
        if (meta) modifiers |= MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        if (ctrl) modifiers |= InputEvent.CTRL_DOWN_MASK;
        if (alt) modifiers |= InputEvent.ALT_DOWN_MASK;
        if (shift) modifiers |= InputEvent.SHIFT_DOWN_MASK;
        return KeyStroke.getKeyStroke(keyCode, modifiers);
    }

    // ── 规范字符串 → 显示文本 ──

    public static String toDisplayText(String spec) {
        if (spec == null || spec.isBlank()) return "";
        String[] parts = spec.strip().split("\\s+");
        String keyName = parts[parts.length - 1];
        boolean ctrl = false, alt = false, shift = false, meta = false;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].toLowerCase()) {
                case "ctrl" -> ctrl = true;
                case "alt" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> meta = true;
            }
        }
        if (MAC) {
            StringBuilder sb = new StringBuilder();
            // macOS: meta → ⌘, ctrl → ⌃, alt → ⌥, shift → ⇧
            if (meta) sb.append("⌘");
            if (ctrl) sb.append("⌃");
            if (alt) sb.append("⌥");
            if (shift) sb.append("⇧");
            sb.append(macKeyText(keyName));
            return sb.toString();
        } else {
            List<String> items = new ArrayList<>();
            // 非 macOS: meta 映射为 Ctrl
            if (meta) items.add("Ctrl");
            if (ctrl) items.add("Ctrl");
            if (alt) items.add("Alt");
            if (shift) items.add("Shift");
            items.add(nonMacKeyText(keyName));
            return String.join("+", items);
        }
    }

    // ── JavaFX KeyEvent → 规范字符串 ──

    public static String fromKeyEvent(KeyEvent event) {
        if (event.getCode() == KeyCode.UNDEFINED) return null;
        // 忽略纯修饰键按下
        if (isModifierOnly(event.getCode())) return null;

        List<String> parts = new ArrayList<>();
        if (event.isControlDown()) parts.add("ctrl");
        if (event.isAltDown()) parts.add("alt");
        if (event.isShiftDown()) parts.add("shift");
        if (event.isMetaDown()) parts.add("meta");

        // 使用 SHORTCUT_DOWN 时，macOS 上 meta 已按，其他平台 ctrl 已按
        // 但 fromKeyEvent 不使用 SHORTCUT_DOWN，直接按实际修饰键记录
        String keyName = keyCodeToCanonical(event.getCode());
        parts.add(keyName);
        return String.join(" ", parts);
    }

    // ── 内部辅助方法 ──

    private static KeyCode parseKeyCode(String name) {
        // 特殊键映射
        return switch (name.toUpperCase()) {
            case "UP" -> KeyCode.UP;
            case "DOWN" -> KeyCode.DOWN;
            case "LEFT" -> KeyCode.LEFT;
            case "RIGHT" -> KeyCode.RIGHT;
            case "PAGE_UP" -> KeyCode.PAGE_UP;
            case "PAGE_DOWN" -> KeyCode.PAGE_DOWN;
            case "INSERT" -> KeyCode.INSERT;
            case "DELETE" -> KeyCode.DELETE;
            case "HOME" -> KeyCode.HOME;
            case "END" -> KeyCode.END;
            case "COMMA" -> KeyCode.COMMA;
            case "PERIOD" -> KeyCode.PERIOD;
            case "SLASH" -> KeyCode.SLASH;
            case "BACK_SLASH" -> KeyCode.BACK_SLASH;
            case "SEMICOLON" -> KeyCode.SEMICOLON;
            case "QUOTE" -> KeyCode.QUOTE;
            case "BRACELEFT" -> KeyCode.BRACELEFT;
            case "BRACERIGHT" -> KeyCode.BRACERIGHT;
            case "SPACE" -> KeyCode.SPACE;
            case "ENTER" -> KeyCode.ENTER;
            case "TAB" -> KeyCode.TAB;
            case "ESCAPE" -> KeyCode.ESCAPE;
            case "BACK_SPACE" -> KeyCode.BACK_SPACE;
            case "MINUS" -> KeyCode.MINUS;
            case "EQUALS" -> KeyCode.EQUALS;
            case "PLUS" -> KeyCode.PLUS;
            default -> {
                // 单字母键
                if (name.length() == 1) yield KeyCode.getKeyCode(name.toUpperCase());
                // 尝试直接映射
                KeyCode code = KeyCode.getKeyCode(name);
                yield code != null ? code : KeyCode.getKeyCode(name.toUpperCase());
            }
        };
    }

    private static String keyCodeToCanonical(KeyCode code) {
        // 字母键用单大写字母
        String name = code.getName();
        if (name.length() == 1) return name.toUpperCase();
        // 特殊键用 JavaFX KeyCode 常量名
        return code.name();
    }

    private static int parseSwingKeyCode(String name) {
        return switch (name.toUpperCase()) {
            case "UP" -> KeyEvent.VK_UP;
            case "DOWN" -> KeyEvent.VK_DOWN;
            case "LEFT" -> KeyEvent.VK_LEFT;
            case "RIGHT" -> KeyEvent.VK_RIGHT;
            case "PAGE_UP" -> KeyEvent.VK_PAGE_UP;
            case "PAGE_DOWN" -> KeyEvent.VK_PAGE_DOWN;
            case "INSERT" -> KeyEvent.VK_INSERT;
            case "DELETE" -> KeyEvent.VK_DELETE;
            case "HOME" -> KeyEvent.VK_HOME;
            case "END" -> KeyEvent.VK_END;
            case "COMMA" -> KeyEvent.VK_COMMA;
            case "PERIOD" -> KeyEvent.VK_PERIOD;
            case "SLASH" -> KeyEvent.VK_SLASH;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "ENTER" -> KeyEvent.VK_ENTER;
            case "TAB" -> KeyEvent.VK_TAB;
            case "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "BACK_SPACE" -> KeyEvent.VK_BACK_SPACE;
            case "MINUS" -> KeyEvent.VK_MINUS;
            case "EQUALS" -> KeyEvent.VK_EQUALS;
            default -> {
                // 单字母键
                if (name.length() == 1) yield KeyEvent.getExtendedKeyCodeForChar(name.toUpperCase().charAt(0));
                // 回退
                yield KeyEvent.getExtendedKeyCodeForChar(name.charAt(0));
            }
        };
    }

    private static boolean isModifierOnly(KeyCode code) {
        return code == KeyCode.SHIFT || code == KeyCode.CONTROL
            || code == KeyCode.ALT || code == KeyCode.COMMAND
            || code == KeyCode.META || code == KeyCode.SHORTCUT;
    }

    private static String macKeyText(String keyName) {
        return switch (keyName.toUpperCase()) {
            case "UP" -> "↑";
            case "DOWN" -> "↓";
            case "LEFT" -> "←";
            case "RIGHT" -> "→";
            case "PAGE_UP" -> "⇡";
            case "PAGE_DOWN" -> "⇣";
            case "INSERT" -> "Ins";
            case "DELETE" -> "Del";
            case "BACK_SPACE" -> "⌫";
            case "ENTER" -> "↩";
            case "ESCAPE" -> "Esc";
            case "COMMA" -> ",";
            case "SPACE" -> "Space";
            case "MINUS" -> "-";
            case "EQUALS" -> "=";
            default -> {
                // 单字母键直接返回
                if (keyName.length() == 1) yield keyName.toUpperCase();
                yield keyName;
            }
        };
    }

    private static String nonMacKeyText(String keyName) {
        return switch (keyName.toUpperCase()) {
            case "PAGE_UP" -> "PgUp";
            case "PAGE_DOWN" -> "PgDn";
            case "INSERT" -> "Ins";
            case "DELETE" -> "Del";
            case "UP" -> "Up";
            case "DOWN" -> "Down";
            case "LEFT" -> "Left";
            case "RIGHT" -> "Right";
            case "BACK_SPACE" -> "Backspace";
            case "COMMA" -> ",";
            case "SPACE" -> "Space";
            case "MINUS" -> "-";
            case "EQUALS" -> "=";
            default -> {
                if (keyName.length() == 1) yield keyName.toUpperCase();
                yield keyName;
            }
        };
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/jlshell/core/shortcut/ShortcutConverter.java
git commit -m "feat: add ShortcutConverter for shortcut string conversion"
```

---

### Task 3: ShortcutRegistry Service

**Files:**
- Create: `core/src/main/java/com/jlshell/core/shortcut/ShortcutRegistry.java`

**Interfaces:**
- Consumes: `AppSettingsService`, `ShortcutDefinition`
- Produces:
  - `ShortcutRegistry(AppSettingsService settings)` constructor
  - `List<ShortcutDefinition> definitions()`
  - `List<ShortcutDefinition> definitionsByCategory(String category)`
  - `String getEffectivePrimary(String id)` — user override ?? default
  - `String getEffectiveSecondary(String id)` — user override ?? default
  - `void setUserPrimary(String id, String value)` — null = clear
  - `void setUserSecondary(String id, String value)` — null = clear
  - `void resetAll()` — clear all user customizations
  - `List<ShortcutDefinition> findConflicts(String id, String keystroke)` — returns definitions whose effective shortcut matches, excluding the given id

- [ ] **Step 1: Create ShortcutRegistry**

```java
package com.jlshell.core.shortcut;

import com.jlshell.core.service.AppSettingsService;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 快捷键注册中心。
 * 管理所有快捷键定义和用户自定义值，提供冲突检测。
 */
public class ShortcutRegistry {

    private static final String SETTINGS_PREFIX = "shortcut.";
    private static final String PRIMARY_SUFFIX = ".primary";
    private static final String SECONDARY_SUFFIX = ".secondary";

    private final AppSettingsService settings;
    private final List<ShortcutDefinition> allDefinitions;
    private final Map<String, ShortcutDefinition> definitionById;

    public ShortcutRegistry(AppSettingsService settings) {
        this.settings = settings;
        this.allDefinitions = Collections.unmodifiableList(buildDefinitions());
        this.definitionById = allDefinitions.stream()
                .collect(Collectors.toUnmodifiableMap(ShortcutDefinition::id, Function.identity()));
    }

    public List<ShortcutDefinition> definitions() {
        return allDefinitions;
    }

    public List<ShortcutDefinition> definitionsByCategory(String category) {
        return allDefinitions.stream()
                .filter(d -> category.equals(d.category()))
                .toList();
    }

    public String getEffectivePrimary(String id) {
        String userValue = settings.get(SETTINGS_PREFIX + id + PRIMARY_SUFFIX).orElse(null);
        if (userValue != null && !userValue.isBlank()) return userValue;
        ShortcutDefinition def = definitionById.get(id);
        return def != null ? def.defaultPrimary() : null;
    }

    public String getEffectiveSecondary(String id) {
        String userValue = settings.get(SETTINGS_PREFIX + id + SECONDARY_SUFFIX).orElse(null);
        if (userValue != null && !userValue.isBlank()) return userValue;
        ShortcutDefinition def = definitionById.get(id);
        return def != null ? def.defaultSecondary() : null;
    }

    public void setUserPrimary(String id, String value) {
        if (value == null || value.isBlank()) {
            settings.remove(SETTINGS_PREFIX + id + PRIMARY_SUFFIX);
        } else {
            settings.set(SETTINGS_PREFIX + id + PRIMARY_SUFFIX, value);
        }
    }

    public void setUserSecondary(String id, String value) {
        if (value == null || value.isBlank()) {
            settings.remove(SETTINGS_PREFIX + id + SECONDARY_SUFFIX);
        } else {
            settings.set(SETTINGS_PREFIX + id + SECONDARY_SUFFIX, value);
        }
    }

    public void resetAll() {
        for (ShortcutDefinition def : allDefinitions) {
            settings.remove(SETTINGS_PREFIX + def.id() + PRIMARY_SUFFIX);
            settings.remove(SETTINGS_PREFIX + def.id() + SECONDARY_SUFFIX);
        }
    }

    /**
     * 检查给定快捷键是否与其他定义的有效快捷键冲突。
     * 返回冲突的 ShortcutDefinition 列表（排除自身）。
     */
    public List<ShortcutDefinition> findConflicts(String id, String keystroke) {
        if (keystroke == null || keystroke.isBlank()) return List.of();
        String normalized = keystroke.strip();
        return allDefinitions.stream()
                .filter(d -> !d.id().equals(id))
                .filter(d -> {
                    String primary = getEffectivePrimary(d.id());
                    String secondary = getEffectiveSecondary(d.id());
                    return normalized.equals(normalizeNullable(primary))
                            || normalized.equals(normalizeNullable(secondary));
                })
                .toList();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private static List<ShortcutDefinition> buildDefinitions() {
        return List.of(
            // ── 程序级快捷键 ──
            new ShortcutDefinition("app.newConnection",        "app", "shortcut.app.newConnection",        "meta N",       null),
            new ShortcutDefinition("app.refreshConnections",   "app", "shortcut.app.refreshConnections",   "meta R",       null),
            new ShortcutDefinition("app.quit",                 "app", "shortcut.app.quit",                 "meta Q",       null),
            new ShortcutDefinition("app.toggleSidebar",        "app", "shortcut.app.toggleSidebar",        "meta B",       null),
            new ShortcutDefinition("app.toggleTopBar",         "app", "shortcut.app.toggleTopBar",         "meta T",       null),
            new ShortcutDefinition("app.focusMode",            "app", "shortcut.app.focusMode",            "meta shift F", null),
            new ShortcutDefinition("app.preferences",          "app", "shortcut.app.preferences",          "meta COMMA",   null),

            // ── 终端快捷键 ──
            new ShortcutDefinition("terminal.copy",       "terminal", "shortcut.terminal.copy",       "meta C",         null),
            new ShortcutDefinition("terminal.paste",      "terminal", "shortcut.terminal.paste",      "meta V",         "shift INSERT"),
            new ShortcutDefinition("terminal.clearBuffer","terminal", "shortcut.terminal.clearBuffer","meta K",         "ctrl L"),
            new ShortcutDefinition("terminal.find",       "terminal", "shortcut.terminal.find",       "meta F",         null),
            new ShortcutDefinition("terminal.selectAll",  "terminal", "shortcut.terminal.selectAll",  null,             null),
            new ShortcutDefinition("terminal.pageUp",     "terminal", "shortcut.terminal.pageUp",     "shift PAGE_UP",  null),
            new ShortcutDefinition("terminal.pageDown",   "terminal", "shortcut.terminal.pageDown",   "shift PAGE_DOWN",null),
            new ShortcutDefinition("terminal.lineUp",     "terminal", "shortcut.terminal.lineUp",     "meta UP",        "ctrl UP"),
            new ShortcutDefinition("terminal.lineDown",   "terminal", "shortcut.terminal.lineDown",   "meta DOWN",      "ctrl DOWN")
        );
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/jlshell/core/shortcut/ShortcutRegistry.java
git commit -m "feat: add ShortcutRegistry for centralized shortcut management"
```

---

### Task 4: i18n Keys

**Files:**
- Modify: `ui/src/main/resources/i18n/messages.properties`
- Modify: `ui/src/main/resources/i18n/messages_zh_CN.properties`

**Interfaces:**
- Produces: All i18n keys referenced by the Shortcuts tab UI

- [ ] **Step 1: Add shortcut keys to messages.properties**

Append these lines to `ui/src/main/resources/i18n/messages.properties`:

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

- [ ] **Step 2: Add shortcut keys to messages_zh_CN.properties**

Append these lines to `ui/src/main/resources/i18n/messages_zh_CN.properties`:

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

- [ ] **Step 3: Build to verify**

Run: `mvn compile -pl ui -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/resources/i18n/messages.properties ui/src/main/resources/i18n/messages_zh_CN.properties
git commit -m "feat: add i18n keys for keyboard shortcut configuration"
```

---

### Task 5: Shortcuts Tab in PreferencesDialog

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/dialog/PreferencesDialog.java`

**Interfaces:**
- Consumes:
  - `ShortcutRegistry` — passed via new parameter
  - `ShortcutConverter.toDisplayText(String)` — to display shortcut text
  - `ShortcutConverter.fromKeyEvent(KeyEvent)` — for key recording
  - `ShortcutConverter.toKeyCodeCombination(String)` — for conflict checking
  - `I18nService.get(String)` — for all display text
  - `ThemeService` — for dialog theming
- Produces: `buildShortcutsPane()` method, `TAB_SHORTCUTS = 8` constant

This is the largest task. The implementer needs to:

1. Add `TAB_SHORTCUTS = 8` and shift `TAB_ABOUT` to 9
2. Add `ShortcutRegistry shortcutRegistry` parameter to the `show()` method chain and `buildTabPane()`
3. Add the shortcuts tab in `buildTabPane()` before the about tab
4. Create `buildShortcutsPane()` method with:
   - Search TextField
   - Scrollable VBox with category headers and shortcut rows
   - Each row: name label, primary key button, secondary key button, clear button
   - Key recording on button click
   - Conflict detection on key press
   - Reset to defaults button at bottom

**Key recording implementation:**

When a key button is clicked:
1. Set `recording = true`, store original text
2. Change button text to `i18n.get("shortcut.pressKey")`
3. Add a `KEY_PRESSED` event filter to the `Scene`
4. On key press:
   - If Escape → cancel, restore original text
   - If `ShortcutConverter.fromKeyEvent(event)` returns null (modifier only) → ignore, wait for next key
   - Otherwise → get canonical string → `shortcutRegistry.findConflicts(id, spec)` → if conflicts, show error tooltip and reject → if no conflicts, call `shortcutRegistry.setUserPrimary/setUserSecondary`, update button display text
5. Remove the event filter, set `recording = false`

**Search filtering:**

The search field's text property listener filters the VBox children, hiding rows whose name or display text doesn't match the search query.

- [ ] **Step 1: Add TAB_SHORTCUTS constant and shift TAB_ABOUT**

In `PreferencesDialog.java`, change:
```java
public static final int TAB_ABOUT = 7;
```
to:
```java
public static final int TAB_SHORTCUTS = 7;
public static final int TAB_ABOUT = 8;
```

- [ ] **Step 2: Add ShortcutRegistry parameter to the show() method chain**

Add `ShortcutRegistry shortcutRegistry` parameter to all `show()` overloads that have more than 3 parameters. Pass it through to `buildTabPane()`. In the simplest (7-param) overload, pass `null` for backward compatibility.

In the full `show()` method (the one with all parameters), add `ShortcutRegistry shortcutRegistry` as a new parameter after `AccountService accountService`. Pass it to `buildTabPane()`.

- [ ] **Step 3: Add ShortcutRegistry parameter to buildTabPane() and add the shortcuts tab**

Add `ShortcutRegistry shortcutRegistry` parameter to `buildTabPane()`. Before the `aboutTab` creation, add:

```java
Tab shortcutsTab = new Tab(i18n.get("preferences.tab.shortcuts"));
shortcutsTab.setContent(buildShortcutsPane(shortcutRegistry, i18n, themeService, owner));
tabPane.getTabs().add(shortcutsTab);
```

- [ ] **Step 4: Implement buildShortcutsPane()**

Add the following method to `PreferencesDialog`:

```java
private static VBox buildShortcutsPane(ShortcutRegistry registry, I18nService i18n,
                                        ThemeService themeService, Stage owner) {
    VBox container = new VBox(10);
    container.setPadding(new Insets(14, 18, 14, 18));

    // ── 搜索框 ──
    TextField searchField = new TextField();
    searchField.setPromptText(i18n.get("shortcut.search.prompt"));
    searchField.getStyleClass().add("shortcut-search");

    // ── 快捷键列表 ──
    VBox listBox = new VBox(4);
    ScrollPane scroll = new ScrollPane(listBox);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.getStyleClass().add("shortcut-list-scroll");

    // 按分类构建行
    Map<String, List<VBox>> rowsByCategory = new LinkedHashMap<>();
    for (ShortcutDefinition def : registry.definitions()) {
        rowsByCategory.computeIfAbsent(def.category(), k -> new ArrayList<>());
        VBox row = buildShortcutRow(def, registry, i18n, themeService, owner);
        rowsByCategory.get(def.category()).add(row);
    }

    // 添加分类标题和行
    Map<String, String> categoryNames = Map.of(
        "app", i18n.get("shortcut.category.app"),
        "terminal", i18n.get("shortcut.category.terminal")
    );
    List<String> categoryOrder = List.of("app", "terminal");

    List<VBox> allRows = new ArrayList<>();
    Map<VBox, ShortcutDefinition> rowToDef = new LinkedHashMap<>();
    for (String category : categoryOrder) {
        Label header = new Label(categoryNames.getOrDefault(category, category));
        header.getStyleClass().add("shortcut-category-header");
        listBox.getChildren().add(header);
        List<VBox> rows = rowsByCategory.getOrDefault(category, List.of());
        for (VBox row : rows) {
            listBox.getChildren().add(row);
            allRows.add(row);
            // 通过 userData 关联 definition
            ShortcutDefinition def = (ShortcutDefinition) row.getUserData();
            rowToDef.put(row, def);
        }
    }

    // ── 搜索过滤 ──
    searchField.textProperty().addListener((obs, oldVal, newText) -> {
        String query = newText == null ? "" : newText.strip().toLowerCase();
        for (var entry : rowToDef.entrySet()) {
            VBox row = entry.getKey();
            ShortcutDefinition def = entry.getValue();
            if (query.isEmpty()) {
                row.setVisible(true);
                row.setManaged(true);
            } else {
                String name = i18n.get(def.nameKey()).toLowerCase();
                String primary = ShortcutConverter.toDisplayText(registry.getEffectivePrimary(def.id())).toLowerCase();
                String secondary = ShortcutConverter.toDisplayText(registry.getEffectiveSecondary(def.id())).toLowerCase();
                boolean match = name.contains(query) || primary.contains(query) || secondary.contains(query);
                row.setVisible(match);
                row.setManaged(match);
            }
        }
        // 隐藏空分类标题
        for (String category : categoryOrder) {
            String headerText = categoryNames.getOrDefault(category, category);
            // 查找对应的 header Label
            for (int i = 0; i < listBox.getChildren().size(); i++) {
                javafx.scene.Node child = listBox.getChildren().get(i);
                if (child instanceof Label label && headerText.equals(label.getText())) {
                    // 检查后续非 Label 子节点是否有可见的
                    boolean hasVisible = false;
                    for (int j = i + 1; j < listBox.getChildren().size(); j++) {
                        javafx.scene.Node next = listBox.getChildren().get(j);
                        if (next instanceof Label) break; // 下一个分类标题
                        if (next.isVisible()) { hasVisible = true; break; }
                    }
                    label.setVisible(hasVisible || query.isEmpty());
                    label.setManaged(hasVisible || query.isEmpty());
                    break;
                }
            }
        }
    });

    // ── 恢复默认按钮 ──
    Button resetBtn = new Button(i18n.get("shortcut.resetDefaults"));
    resetBtn.getStyleClass().add("shortcut-reset-button");
    resetBtn.setOnAction(e -> {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                i18n.get("shortcut.resetDefaults.confirm"),
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle(i18n.get("preferences.title"));
        confirm.setHeaderText(null);
        if (owner != null) confirm.initOwner(owner);
        themeService.applyToDialog(confirm);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                registry.resetAll();
                // 重建 UI
                container.getChildren().clear();
                container.getChildren().addAll(searchField, scroll, resetBtn);
                // 重建列表内容
                listBox.getChildren().clear();
                Map<String, List<VBox>> newRows = new LinkedHashMap<>();
                Map<VBox, ShortcutDefinition> newRowToDef = new LinkedHashMap<>();
                for (ShortcutDefinition def : registry.definitions()) {
                    VBox row = buildShortcutRow(def, registry, i18n, themeService, owner);
                    newRows.computeIfAbsent(def.category(), k -> new ArrayList<>());
                    newRows.get(def.category()).add(row);
                    newRowToDef.put(row, def);
                }
                for (String category : categoryOrder) {
                    Label header = new Label(categoryNames.getOrDefault(category, category));
                    header.getStyleClass().add("shortcut-category-header");
                    listBox.getChildren().add(header);
                    for (VBox row : newRows.getOrDefault(category, List.of())) {
                        listBox.getChildren().add(row);
                    }
                }
                // 重新绑定搜索
                searchField.textProperty().addListener((obs2, old2, new2) -> {
                    String q = new2 == null ? "" : new2.strip().toLowerCase();
                    for (var entry2 : newRowToDef.entrySet()) {
                        VBox r = entry2.getKey();
                        ShortcutDefinition d = entry2.getValue();
                        if (q.isEmpty()) { r.setVisible(true); r.setManaged(true); }
                        else {
                            String n = i18n.get(d.nameKey()).toLowerCase();
                            boolean m = n.contains(q)
                                    || ShortcutConverter.toDisplayText(registry.getEffectivePrimary(d.id())).toLowerCase().contains(q)
                                    || ShortcutConverter.toDisplayText(registry.getEffectiveSecondary(d.id())).toLowerCase().contains(q);
                            r.setVisible(m); r.setManaged(m);
                        }
                    }
                });
            }
        });
    });

    HBox bottomBar = new HBox();
    bottomBar.setAlignment(Pos.CENTER_RIGHT);
    bottomBar.getChildren().add(resetBtn);

    VBox.setVgrow(scroll, Priority.ALWAYS);
    container.getChildren().addAll(searchField, scroll, bottomBar);
    return container;
}
```

- [ ] **Step 5: Implement buildShortcutRow()**

```java
private static VBox buildShortcutRow(ShortcutDefinition def, ShortcutRegistry registry,
                                      I18nService i18n, ThemeService themeService, Stage owner) {
    VBox row = new VBox(2);
    row.setUserData(def);
    row.getStyleClass().add("shortcut-row");

    // 名称
    Label nameLabel = new Label(i18n.get(def.nameKey()));
    nameLabel.getStyleClass().add("shortcut-name");
    HBox.setHgrow(nameLabel, Priority.ALWAYS);

    // 主快捷键按钮
    String primarySpec = registry.getEffectivePrimary(def.id());
    Button primaryBtn = new Button(primarySpec != null ? ShortcutConverter.toDisplayText(primarySpec) : i18n.get("shortcut.unassigned"));
    primaryBtn.getStyleClass().add("shortcut-key-button");
    primaryBtn.setUserData("primary");

    // 备用快捷键按钮
    String secondarySpec = registry.getEffectiveSecondary(def.id());
    Button secondaryBtn = new Button(secondarySpec != null ? ShortcutConverter.toDisplayText(secondarySpec) : i18n.get("shortcut.unassigned"));
    secondaryBtn.getStyleClass().add("shortcut-key-button");
    secondaryBtn.setUserData("secondary");

    // 清除按钮
    Button clearBtn = new Button("×");
    clearBtn.getStyleClass().add("shortcut-clear-button");
    clearBtn.setOnAction(e -> {
        registry.setUserPrimary(def.id(), null);
        registry.setUserSecondary(def.id(), null);
        primaryBtn.setText(i18n.get("shortcut.unassigned"));
        secondaryBtn.setText(i18n.get("shortcut.unassigned"));
        primaryBtn.setStyle(null);
        secondaryBtn.setStyle(null);
    });

    // 快捷键录制
    setupKeyRecording(primaryBtn, def, "primary", registry, i18n, themeService, owner);
    setupKeyRecording(secondaryBtn, def, "secondary", registry, i18n, themeService, owner);

    HBox hbox = new HBox(8, nameLabel, primaryBtn, secondaryBtn, clearBtn);
    hbox.setAlignment(Pos.CENTER_LEFT);
    row.getChildren().add(hbox);
    return row;
}
```

- [ ] **Step 6: Implement setupKeyRecording()**

```java
private static void setupKeyRecording(Button btn, ShortcutDefinition def, String slot,
                                       ShortcutRegistry registry, I18nService i18n,
                                       ThemeService themeService, Stage owner) {
    btn.setOnAction(e -> {
        String originalText = btn.getText();
        btn.setText(i18n.get("shortcut.pressKey"));
        btn.setStyle("-fx-border-color: -fx-accent; -fx-border-width: 2;");

        javafx.event.EventHandler<javafx.scene.input.KeyEvent> filter = new javafx.event.EventHandler<>() {
            @Override
            public void handle(javafx.scene.input.KeyEvent event) {
                // Escape 取消
                if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    event.consume();
                    btn.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this);
                    btn.setText(originalText);
                    btn.setStyle(null);
                    return;
                }

                String spec = ShortcutConverter.fromKeyEvent(event);
                if (spec == null) return; // 纯修饰键，等待下一个键

                event.consume();

                // 冲突检测
                List<ShortcutDefinition> conflicts = registry.findConflicts(def.id(), spec);
                if (!conflicts.isEmpty()) {
                    ShortcutDefinition conflict = conflicts.get(0);
                    String conflictName = i18n.get(conflict.nameKey());
                    btn.setStyle("-fx-border-color: red; -fx-border-width: 2;");
                    btn.setTooltip(new javafx.scene.control.Tooltip(
                            i18n.get("shortcut.conflict", conflictName)));
                    // 短暂显示后恢复
                    javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                    pause.setOnFinished(ev -> {
                        btn.setText(originalText);
                        btn.setStyle(null);
                        btn.setTooltip(null);
                    });
                    pause.play();
                    btn.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this);
                    return;
                }

                // 无冲突，保存
                if ("primary".equals(slot)) {
                    registry.setUserPrimary(def.id(), spec);
                } else {
                    registry.setUserSecondary(def.id(), spec);
                }

                btn.setText(ShortcutConverter.toDisplayText(spec));
                btn.setStyle(null);
                btn.setTooltip(null);
                btn.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this);
            }
        };

        btn.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, filter);

        // 焦点丢失时取消录制
        btn.focusedProperty().addListener(new javafx.beans.value.ChangeListener<Boolean>() {
            @Override
            public void changed(javafx.beans.ObservableValue<? extends Boolean> obs, Boolean oldVal, Boolean newVal) {
                if (!newVal) {
                    btn.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, filter);
                    btn.setText(originalText);
                    btn.setStyle(null);
                    btn.focusedProperty().removeListener(this);
                }
            }
        });
    });
}
```

- [ ] **Step 7: Add missing imports to PreferencesDialog**

Add these imports:
```java
import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutDefinition;
import com.jlshell.core.shortcut.ShortcutRegistry;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 8: Build to verify compilation**

Run: `mvn compile -pl ui -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/dialog/PreferencesDialog.java
git commit -m "feat: add Shortcuts tab to Preferences dialog with search and key recording"
```

---

### Task 6: Wire ShortcutRegistry Through MainWindow and AppContext

**Files:**
- Modify: `ui/src/main/java/com/jlshell/ui/view/MainWindow.java`
- Modify: `app/src/main/java/com/jlshell/app/AppContext.java`

**Interfaces:**
- Consumes:
  - `ShortcutRegistry` from `core.shortcut`
  - `ShortcutConverter.toKeyCodeCombination(String)` from `core.shortcut`

This task:
1. Adds `ShortcutRegistry` field to `MainWindow` and passes it through constructor
2. Changes `installApplicationShortcuts()` to read from `ShortcutRegistry`
3. Adds `refreshShortcuts()` method to re-install shortcuts after changes
4. Updates `openPreferences()` to pass `ShortcutRegistry` to `PreferencesDialog.show()`
5. Creates `ShortcutRegistry` in `AppContext` and passes it to `MainWindow` and `JlshellSettingsProvider`

- [ ] **Step 1: Add ShortcutRegistry field and constructor parameter to MainWindow**

In `MainWindow.java`, add a new field:
```java
private final ShortcutRegistry shortcutRegistry;
```

Add `ShortcutRegistry shortcutRegistry` as the last parameter to the `MainWindow` constructor. In the constructor body, add:
```java
this.shortcutRegistry = shortcutRegistry;
```

- [ ] **Step 2: Rewrite installApplicationShortcuts() to use ShortcutRegistry**

Replace the `installApplicationShortcuts` method and its helpers:

```java
/** 已安装的快捷键事件过滤器列表，用于 refreshShortcuts() 时移除。 */
private final List<EventHandler<KeyEvent>> installedShortcutFilters = new ArrayList<>();

private void installApplicationShortcuts(Scene scene, Stage stage) {
    installShortcutFromRegistry(scene, "app.newConnection", () -> createConnection(stage));
    installShortcutFromRegistry(scene, "app.refreshConnections", this::loadConnections);
    installShortcutFromRegistry(scene, "app.quit", stage::close);
    installShortcutFromRegistry(scene, "app.toggleSidebar", this::toggleSidebar);
    installShortcutFromRegistry(scene, "app.toggleTopBar", this::toggleTopBarCollapse);
    installShortcutFromRegistry(scene, "app.focusMode", this::toggleFocusMode);
    installShortcutFromRegistry(scene, "app.preferences", () -> openPreferences(stage));
}

private void installShortcutFromRegistry(Scene scene, String shortcutId, Runnable action) {
    String spec = shortcutRegistry.getEffectivePrimary(shortcutId);
    if (spec == null || spec.isBlank()) return;
    KeyCodeCombination combo = ShortcutConverter.toKeyCodeCombination(spec);
    if (combo == null) return;
    EventHandler<KeyEvent> filter = event -> {
        if (event.isConsumed() || !combo.match(event)) return;
        // 当终端获得焦点时，Ctrl+字母快捷键交给终端处理
        if (isTerminalFocusOwner(scene) && event.isControlDown() && !event.isMetaDown()) return;
        action.run();
        event.consume();
    };
    scene.addEventFilter(KeyEvent.KEY_PRESSED, filter);
    installedShortcutFilters.add(filter);
}

/** 重新安装所有程序级快捷键（快捷键设置变更后调用）。 */
public void refreshShortcuts(Scene scene, Stage stage) {
    // 移除旧的过滤器
    for (EventHandler<KeyEvent> filter : installedShortcutFilters) {
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, filter);
    }
    installedShortcutFilters.clear();
    // 重新安装
    installApplicationShortcuts(scene, stage);
}
```

Add the import at the top of MainWindow:
```java
import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutRegistry;
```

- [ ] **Step 3: Update openPreferences() to pass ShortcutRegistry**

Find the `openPreferences(Stage stage, int initialTabIndex)` method and update the `PreferencesDialog.show()` call to include `shortcutRegistry`:

```java
private void openPreferences(Stage stage, int initialTabIndex) {
    String selectedSessionId = selectedApiSessionId();
    PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
            connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager,
            pluginManager, selectedSessionId, memoryReclaimService, accountService, shortcutRegistry,
            initialTabIndex);
    // 导入后刷新侧边栏
    loadConnections();
    // 应用可能变更的 UI 字体设置
    applyUiFontSettings();
    // 快捷键可能已变更，重新安装
    refreshShortcuts(stage.getScene(), stage);
}
```

- [ ] **Step 4: Update AppContext to create ShortcutRegistry and pass it**

In `AppContext.java`, after `AppSettingsService appSettingsService` creation (step 3), add:

```java
import com.jlshell.core.shortcut.ShortcutRegistry;
```

Then after `UpdateService updateService = ...` line, add:
```java
ShortcutRegistry shortcutRegistry = new ShortcutRegistry(appSettingsService);
```

Update the `MainWindow` constructor call to add `shortcutRegistry` as the last parameter:
```java
mainWindow = new MainWindow(
        viewModel,
        connectionProfileService,
        sessionManager,
        terminalViewFactory,
        fontProfileService,
        appSettingsService,
        sftpService,
        themeService,
        i18nService,
        localShellLauncher,
        executor,
        vaultService,
        5,
        pluginManager,
        programPluginManager,
        apiServer,
        capabilityBus,
        storageFactory,
        memoryReclaimService,
        updateService,
        accountService,
        shortcutRegistry
);
```

- [ ] **Step 5: Build to verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add ui/src/main/java/com/jlshell/ui/view/MainWindow.java app/src/main/java/com/jlshell/app/AppContext.java
git commit -m "feat: wire ShortcutRegistry through MainWindow and AppContext"
```

---

### Task 7: Integrate ShortcutRegistry into JlshellSettingsProvider

**Files:**
- Modify: `terminal/src/main/java/com/jlshell/terminal/support/JlshellSettingsProvider.java`

**Interfaces:**
- Consumes:
  - `ShortcutRegistry` from `core.shortcut`
  - `ShortcutConverter.toKeyStroke(String)` from `core.shortcut`
- Produces:
  - `JlshellSettingsProvider` now overrides all `getXxxActionPresentation()` methods reading from `ShortcutRegistry`
  - `refreshActions()` method to update mutable KeyStroke lists in-place

- [ ] **Step 1: Add ShortcutRegistry to JlshellSettingsProvider**

Add a new constructor parameter `ShortcutRegistry shortcutRegistry`. Store it as a field. Also store references to the mutable KeyStroke lists for each action so they can be updated later:

```java
package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutRegistry;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import javax.swing.KeyStroke;

/**
 * 可变终端设置提供器。
 * 后续字体或主题切换时，不必重建整个 SSH 会话。
 */
public class JlshellSettingsProvider extends DefaultSettingsProvider {

    private final AtomicReference<FontProfile> fontProfile;
    private final AtomicReference<TerminalColorScheme> colorScheme;
    private final AtomicReference<ColorPalette> colorPalette;
    private final AtomicReference<TerminalRuntimeSettings> runtimeSettings;
    private final ShortcutRegistry shortcutRegistry;

    // 可变的 KeyStroke 列表引用，用于 refreshActions() 时原地更新
    private final List<KeyStroke> copyStrokes;
    private final List<KeyStroke> pasteStrokes;
    private final List<KeyStroke> clearBufferStrokes;
    private final List<KeyStroke> findStrokes;
    private final List<KeyStroke> selectAllStrokes;
    private final List<KeyStroke> pageUpStrokes;
    private final List<KeyStroke> pageDownStrokes;
    private final List<KeyStroke> lineUpStrokes;
    private final List<KeyStroke> lineDownStrokes;

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme) {
        this(fontProfile, colorScheme, TerminalRuntimeSettings.defaults(), null);
    }

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme,
                                   TerminalRuntimeSettings runtimeSettings) {
        this(fontProfile, colorScheme, runtimeSettings, null);
    }

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme,
                                   TerminalRuntimeSettings runtimeSettings,
                                   ShortcutRegistry shortcutRegistry) {
        this.fontProfile = new AtomicReference<>(fontProfile);
        this.colorScheme = new AtomicReference<>(colorScheme);
        this.colorPalette = new AtomicReference<>(buildPalette(colorScheme));
        this.runtimeSettings = new AtomicReference<>(
                runtimeSettings == null ? TerminalRuntimeSettings.defaults() : runtimeSettings);
        this.shortcutRegistry = shortcutRegistry;

        // 初始化可变 KeyStroke 列表
        this.copyStrokes = buildKeyStrokes("terminal.copy");
        this.pasteStrokes = buildKeyStrokes("terminal.paste");
        this.clearBufferStrokes = buildKeyStrokes("terminal.clearBuffer");
        this.findStrokes = buildKeyStrokes("terminal.find");
        this.selectAllStrokes = buildKeyStrokes("terminal.selectAll");
        this.pageUpStrokes = buildKeyStrokes("terminal.pageUp");
        this.pageDownStrokes = buildKeyStrokes("terminal.pageDown");
        this.lineUpStrokes = buildKeyStrokes("terminal.lineUp");
        this.lineDownStrokes = buildKeyStrokes("terminal.lineDown");
    }

    /**
     * 从 ShortcutRegistry 刷新所有终端快捷键的 KeyStroke 列表。
     * 因为 TerminalActionPresentation 存储的是列表引用，原地修改即可生效。
     */
    public void refreshActions() {
        if (shortcutRegistry == null) return;
        refreshKeyStrokes(copyStrokes, "terminal.copy");
        refreshKeyStrokes(pasteStrokes, "terminal.paste");
        refreshKeyStrokes(clearBufferStrokes, "terminal.clearBuffer");
        refreshKeyStrokes(findStrokes, "terminal.find");
        refreshKeyStrokes(selectAllStrokes, "terminal.selectAll");
        refreshKeyStrokes(pageUpStrokes, "terminal.pageUp");
        refreshKeyStrokes(pageDownStrokes, "terminal.pageDown");
        refreshKeyStrokes(lineUpStrokes, "terminal.lineUp");
        refreshKeyStrokes(lineDownStrokes, "terminal.lineDown");
    }

    private List<KeyStroke> buildKeyStrokes(String id) {
        List<KeyStroke> strokes = new ArrayList<>();
        if (shortcutRegistry != null) {
            String primary = shortcutRegistry.getEffectivePrimary(id);
            String secondary = shortcutRegistry.getEffectiveSecondary(id);
            if (primary != null) strokes.add(ShortcutConverter.toKeyStroke(primary));
            if (secondary != null) strokes.add(ShortcutConverter.toKeyStroke(secondary));
        } else {
            // 无 Registry 时使用默认（父类逻辑）
            // 保持向后兼容
        }
        return strokes;
    }

    private void refreshKeyStrokes(List<KeyStroke> target, String id) {
        target.clear();
        String primary = shortcutRegistry.getEffectivePrimary(id);
        String secondary = shortcutRegistry.getEffectiveSecondary(id);
        if (primary != null) target.add(ShortcutConverter.toKeyStroke(primary));
        if (secondary != null) target.add(ShortcutConverter.toKeyStroke(secondary));
    }

    // ── 快捷键覆写 ──

    @Override
    public TerminalActionPresentation getCopyActionPresentation() {
        if (shortcutRegistry == null) return super.getCopyActionPresentation();
        return new TerminalActionPresentation("Copy", copyStrokes);
    }

    @Override
    public TerminalActionPresentation getPasteActionPresentation() {
        if (shortcutRegistry == null) {
            // 保留原有 Shift+Insert 逻辑
            List<KeyStroke> strokes = new ArrayList<>(super.getPasteActionPresentation().getKeyStrokes());
            strokes.add(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK));
            return new TerminalActionPresentation("Paste", strokes);
        }
        return new TerminalActionPresentation("Paste", pasteStrokes);
    }

    @Override
    public TerminalActionPresentation getClearBufferActionPresentation() {
        if (shortcutRegistry == null) return super.getClearBufferActionPresentation();
        return new TerminalActionPresentation("Clear Buffer", clearBufferStrokes);
    }

    @Override
    public TerminalActionPresentation getFindActionPresentation() {
        if (shortcutRegistry == null) return super.getFindActionPresentation();
        return new TerminalActionPresentation("Find", findStrokes);
    }

    @Override
    public TerminalActionPresentation getSelectAllActionPresentation() {
        if (shortcutRegistry == null) return super.getSelectAllActionPresentation();
        return new TerminalActionPresentation("Select All", selectAllStrokes);
    }

    @Override
    public TerminalActionPresentation getPageUpActionPresentation() {
        if (shortcutRegistry == null) return super.getPageUpActionPresentation();
        return new TerminalActionPresentation("Page Up", pageUpStrokes);
    }

    @Override
    public TerminalActionPresentation getPageDownActionPresentation() {
        if (shortcutRegistry == null) return super.getPageDownActionPresentation();
        return new TerminalActionPresentation("Page Down", pageDownStrokes);
    }

    @Override
    public TerminalActionPresentation getLineUpActionPresentation() {
        if (shortcutRegistry == null) return super.getLineUpActionPresentation();
        return new TerminalActionPresentation("Line Up", lineUpStrokes);
    }

    @Override
    public TerminalActionPresentation getLineDownActionPresentation() {
        if (shortcutRegistry == null) return super.getLineDownActionPresentation();
        return new TerminalActionPresentation("Line Down", lineDownStrokes);
    }

    // ── 以下为原有方法，保持不变 ──

    public void updateFontProfile(FontProfile updatedFontProfile) {
        fontProfile.set(updatedFontProfile);
    }

    public void updateColorScheme(TerminalColorScheme updatedColorScheme) {
        colorScheme.set(updatedColorScheme);
        colorPalette.set(buildPalette(updatedColorScheme));
    }

    public Color backgroundColor() {
        return colorScheme.get().background();
    }

    public Color foregroundColor() {
        return colorScheme.get().foreground();
    }

    public double opacity() {
        return colorScheme.get().opacity();
    }

    public TextStyle defaultTextStyle() {
        return new TextStyle(toTerminalColor(colorScheme.get().foreground()), toTerminalColor(colorScheme.get().background()));
    }

    @Override
    public com.jediterm.terminal.TerminalColor getDefaultBackground() {
        return toTerminalColor(colorScheme.get().background());
    }

    @Override
    public com.jediterm.terminal.TerminalColor getDefaultForeground() {
        return toTerminalColor(colorScheme.get().foreground());
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return colorPalette.get();
    }

    @Override
    public Font getTerminalFont() {
        FontProfile profile = fontProfile.get();
        return new Font(profile.family(), Font.PLAIN, Math.max(1, (int) Math.round(profile.size())));
    }

    @Override
    public float getTerminalFontSize() {
        return (float) fontProfile.get().size();
    }

    @Override
    public float getLineSpacing() {
        return (float) fontProfile.get().lineSpacing();
    }

    @Override
    public TextStyle getSelectionColor() {
        TerminalColorScheme scheme = colorScheme.get();
        return new TextStyle(toTerminalColor(scheme.selectionForeground()), toTerminalColor(scheme.selectionBackground()));
    }

    @Override
    public TextStyle getFoundPatternColor() {
        TerminalColorScheme scheme = colorScheme.get();
        return new TextStyle(toTerminalColor(scheme.searchMatchForeground()), toTerminalColor(scheme.searchMatchBackground()));
    }

    @Override
    public TextStyle getHyperlinkColor() {
        TerminalColor foreground = toTerminalColor(colorScheme.get().hyperlinkColor());
        return new TextStyle(foreground, null);
    }

    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
        return HyperlinkStyle.HighlightMode.HOVER;
    }

    @Override
    public boolean altSendsEscape() {
        return true;
    }

    @Override
    public boolean DECCompatibilityMode() {
        return true;
    }

    @Override
    public boolean useAntialiasing() {
        return true;
    }

    @Override
    public int maxRefreshRate() {
        return 60;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return runtimeSettings.get().scrollbackLines();
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public boolean copyOnSelect() {
        return false;
    }

    @Override
    public boolean pasteOnMiddleMouseClick() {
        return false;
    }

    @Override
    public boolean scrollToBottomOnTyping() {
        return true;
    }

    @Override
    public boolean enableMouseReporting() {
        return true;
    }

    private TerminalColor toTerminalColor(Color color) {
        return TerminalColor.rgb(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static ColorPalette buildPalette(TerminalColorScheme scheme) {
        java.awt.Color[] ansi = scheme.ansiColors();
        com.jediterm.core.Color[] jediColors = new com.jediterm.core.Color[16];
        for (int i = 0; i < 16; i++) {
            jediColors[i] = new com.jediterm.core.Color(ansi[i].getRed(), ansi[i].getGreen(), ansi[i].getBlue());
        }
        return new ColorPalette() {
            @Override
            protected com.jediterm.core.Color getForegroundByColorIndex(int colorIndex) {
                return jediColors[colorIndex];
            }

            @Override
            protected com.jediterm.core.Color getBackgroundByColorIndex(int colorIndex) {
                return jediColors[colorIndex];
            }
        };
    }
}
```

- [ ] **Step 2: Update AppContext to pass ShortcutRegistry to JlshellSettingsProvider**

In `AppContext.java`, the `JediTermTerminalViewFactory` creates `JlshellSettingsProvider` internally. We need to check how it's created.

Find the `JediTermTerminalViewFactory` constructor and add `shortcutRegistry` parameter:

The `JediTermTerminalViewFactory` constructor is at line 156-157 of AppContext:
```java
JediTermTerminalViewFactory terminalViewFactory = new JediTermTerminalViewFactory(
        fontProfileService, executor, i18nService::get, BundledFontLoader::ensureAwtRegistered);
```

This needs to be updated. But first check what JediTermTerminalViewFactory looks like — see Task 8.

- [ ] **Step 3: Build to verify compilation**

Run: `mvn compile -pl terminal -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add terminal/src/main/java/com/jlshell/terminal/support/JlshellSettingsProvider.java
git commit -m "feat: integrate ShortcutRegistry into JlshellSettingsProvider for terminal shortcuts"
```

---

### Task 8: Pass ShortcutRegistry to JediTermTerminalViewFactory and LocalShellLauncher

**Files:**
- Modify: `terminal/src/main/java/com/jlshell/terminal/service/JediTermTerminalViewFactory.java`
- Modify: `ui/src/main/java/com/jlshell/ui/service/LocalShellLauncher.java`
- Modify: `app/src/main/java/com/jlshell/app/AppContext.java`

**Interfaces:**
- Consumes: `ShortcutRegistry` from `core.shortcut`

Both `JediTermTerminalViewFactory` and `LocalShellLauncher` create `JlshellSettingsProvider` internally. They need `ShortcutRegistry` to pass it through.

- [ ] **Step 1: Read JediTermTerminalViewFactory to understand its structure**

Read the file at `terminal/src/main/java/com/jlshell/terminal/service/JediTermTerminalViewFactory.java`. It will have a constructor and methods that create `JlshellSettingsProvider`. Add `ShortcutRegistry` as a constructor parameter (nullable for backward compat) and pass it when constructing `JlshellSettingsProvider`.

- [ ] **Step 2: Read LocalShellLauncher to understand its structure**

Read the file at `ui/src/main/java/com/jlshell/ui/service/LocalShellLauncher.java`. Similarly add `ShortcutRegistry` parameter and pass it when constructing `JlshellSettingsProvider`.

- [ ] **Step 3: Update both files to accept and pass ShortcutRegistry**

For each file:
1. Add `import com.jlshell.core.shortcut.ShortcutRegistry;`
2. Add `ShortcutRegistry shortcutRegistry` as a constructor parameter (after existing params, can be nullable)
3. Store as field
4. Pass to `JlshellSettingsProvider` constructor where it's created

- [ ] **Step 4: Update AppContext to pass ShortcutRegistry**

In `AppContext.java`, update the `JediTermTerminalViewFactory` and `LocalShellLauncher` constructor calls:

```java
JediTermTerminalViewFactory terminalViewFactory = new JediTermTerminalViewFactory(
        fontProfileService, executor, i18nService::get, BundledFontLoader::ensureAwtRegistered,
        shortcutRegistry);
```

```java
LocalShellLauncher localShellLauncher = new LocalShellLauncher(
        fontProfileService, executor, i18nService, BundledFontLoader::ensureAwtRegistered,
        shortcutRegistry);
```

- [ ] **Step 5: Build to verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add terminal/src/main/java/com/jlshell/terminal/service/JediTermTerminalViewFactory.java \
      ui/src/main/java/com/jlshell/ui/service/LocalShellLauncher.java \
      app/src/main/java/com/jlshell/app/AppContext.java
git commit -m "feat: pass ShortcutRegistry to JediTermTerminalViewFactory and LocalShellLauncher"
```

---

### Task 9: Final Build Verification and Commit Cleanup

**Files:**
- No new files — verification only

- [ ] **Step 1: Full build with tests**

Run: `mvn clean package -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run the application**

Run: `mvn install -DskipTests -q && mvn javafx:run -pl app`
Expected: Application launches. Open Preferences → Shortcuts tab visible with all shortcut rows. Click a key button to test recording. Click "Reset to Defaults" to test reset.

- [ ] **Step 3: Verify keyboard shortcuts still work**

In the running application:
1. Press Cmd/Ctrl+N — should open New Connection dialog
2. Press Cmd/Ctrl+, — should open Preferences
3. Open an SSH session, right-click in terminal — shortcut labels should display correctly

- [ ] **Step 4: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: address integration issues from keyboard shortcut feature"
```
