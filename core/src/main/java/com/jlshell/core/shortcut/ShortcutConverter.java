package com.jlshell.core.shortcut;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 快捷键规范字符串与平台特定表示之间的转换工具（Swing 部分）。
 * 规范格式：修饰键按 ctrl → alt → shift → meta 排列，空格分隔，最后是键名。
 * 例如："meta N"、"ctrl shift C"、"alt LEFT"、"shift PAGE_UP"
 *
 * <p>JavaFX 依赖的方法（toKeyCodeCombination、fromKeyEvent）在
 * ui 模块的 {@code com.jlshell.ui.shortcut.FxShortcutConverter} 中，
 * 因为 core 模块不能依赖 JavaFX。</p>
 */
public final class ShortcutConverter {

    private ShortcutConverter() {}

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // ── 规范字符串 → Swing KeyStroke ──

    /**
     * 将规范快捷键字符串转换为 Swing KeyStroke。
     * macOS 上 meta 映射为 Cmd (META_DOWN_MASK)，其他平台映射为 Ctrl (CTRL_DOWN_MASK)。
     *
     * @param spec 规范快捷键字符串，如 "meta N"、"ctrl shift C"；null 或空白返回 null
     * @return 对应的 KeyStroke，或 null
     */
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

    /**
     * 将规范快捷键字符串转换为平台友好的显示文本。
     * macOS 使用 ⌘⌃⌥⇧ 符号并以窄空格分隔，其他平台使用 Ctrl/Alt/Shift
     * 文本并以带空格的 "+" 连接，避免组合键挤在一起。
     *
     * @param spec 规范快捷键字符串；null 或空白返回空字符串
     * @return 平台友好的显示文本
     */
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
            List<String> items = new ArrayList<>();
            // macOS: meta → ⌘, ctrl → ⌃, alt → ⌥, shift → ⇧
            if (meta) items.add("⌘");
            if (ctrl) items.add("⌃");
            if (alt) items.add("⌥");
            if (shift) items.add("⇧");
            items.add(macKeyText(keyName));
            return String.join("\u202f", items);
        } else {
            List<String> items = new ArrayList<>();
            // 非 macOS: meta 映射为 Ctrl
            if (meta) items.add("Ctrl");
            if (ctrl) items.add("Ctrl");
            if (alt) items.add("Alt");
            if (shift) items.add("Shift");
            items.add(nonMacKeyText(keyName));
            return String.join(" + ", items);
        }
    }

    // ── 内部辅助方法 ──

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

    private static String macKeyText(String keyName) {
        return switch (keyName.toUpperCase()) {
            case "UP" -> "↑";         // ↑
            case "DOWN" -> "↓";       // ↓
            case "LEFT" -> "←";       // ←
            case "RIGHT" -> "→";      // →
            case "PAGE_UP" -> "⇡";    // ⇡
            case "PAGE_DOWN" -> "⇣";  // ⇣
            case "INSERT" -> "Ins";
            case "DELETE" -> "Del";
            case "BACK_SPACE" -> "⌫"; // ⌫
            case "ENTER" -> "↩";      // ↩
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
