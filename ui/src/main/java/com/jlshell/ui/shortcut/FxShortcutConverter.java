package com.jlshell.ui.shortcut;

import com.jlshell.core.shortcut.ShortcutConverter;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷键规范字符串与 JavaFX 表示之间的转换工具。
 * 包含需要 JavaFX 依赖的方法（toKeyCodeCombination、fromKeyEvent），
 * 这些方法不能放在 core 模块，因为 core 不依赖 JavaFX。
 *
 * <p>Swing 相关方法（toKeyStroke、toDisplayText）在
 * {@link ShortcutConverter} 中。</p>
 */
public final class FxShortcutConverter {

    private FxShortcutConverter() {}

    // ── 规范字符串 → JavaFX KeyCodeCombination ──

    /**
     * 将规范快捷键字符串转换为 JavaFX KeyCodeCombination。
     * meta 修饰键映射为 SHORTCUT_DOWN（macOS 上为 Cmd，其他平台为 Ctrl）。
     *
     * @param spec 规范快捷键字符串，如 "meta N"、"ctrl shift C"；null 或空白返回 null
     * @return 对应的 KeyCodeCombination，或 null
     */
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

    // ── JavaFX KeyEvent → 规范字符串 ──

    /**
     * 从 JavaFX KeyEvent 提取规范快捷键字符串。
     * 忽略纯修饰键按下（返回 null）。使用实际修饰键状态，不使用 SHORTCUT_DOWN。
     *
     * @param event JavaFX KeyEvent
     * @return 规范快捷键字符串，如 "ctrl shift C"；纯修饰键或未定义键返回 null
     */
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

    private static boolean isModifierOnly(KeyCode code) {
        return code == KeyCode.SHIFT || code == KeyCode.CONTROL
            || code == KeyCode.ALT || code == KeyCode.COMMAND
            || code == KeyCode.META || code == KeyCode.SHORTCUT;
    }
}
