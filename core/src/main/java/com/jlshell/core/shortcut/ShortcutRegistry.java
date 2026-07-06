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
