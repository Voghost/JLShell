package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.UIManager;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

/**
 * 暴露受保护的字体/布局刷新能力。
 * 过滤 macOS SwingNode 下修饰键单独触发的 keyChar=\0 噪音事件。
 * 覆盖右键菜单，使其与应用主题颜色一致并支持多语言。
 */
public class RefreshableTerminalPanel extends TerminalPanel {

    // JediTerm 硬编码的英文菜单项名 → i18n key 映射
    private static final Map<String, String> ACTION_KEY_MAP = Map.of(
            "Copy",         "terminal.action.copy",
            "Paste",        "terminal.action.paste",
            "Clear Buffer", "terminal.action.clearBuffer",
            "Select All",   "terminal.action.selectAll"
    );

    private final JlshellSettingsProvider jlshellSettings;
    private final Function<String, String> i18n;

    public RefreshableTerminalPanel(
            SettingsProvider settingsProvider,
            TerminalTextBuffer terminalTextBuffer,
            StyleState styleState,
            JlshellSettingsProvider jlshellSettings,
            Function<String, String> i18n
    ) {
        super(settingsProvider, terminalTextBuffer, styleState);
        this.jlshellSettings = jlshellSettings;
        this.i18n = i18n;
    }

    public void refreshVisuals() {
        reinitFontAndResize();
        repaint();
    }

    @Override
    protected JPopupMenu createPopupMenu(TerminalActionProvider provider) {
        Color bg      = jlshellSettings.backgroundColor();
        Color fg      = jlshellSettings.foregroundColor();
        Color hover   = blend(bg, fg, 0.15f);
        Color border  = blend(bg, fg, 0.25f);

        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(bg);
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1),
                BorderFactory.createEmptyBorder(3, 0, 3, 0)
        ));
        menu.setOpaque(true);

        // 临时覆盖 UIManager，让 JMenuItem 默认颜色跟随主题
        UIManager.put("MenuItem.background",          bg);
        UIManager.put("MenuItem.foreground",          fg);
        UIManager.put("MenuItem.selectionBackground", hover);
        UIManager.put("MenuItem.selectionForeground", fg);
        UIManager.put("MenuItem.disabledForeground",  blend(bg, fg, 0.4f));
        UIManager.put("PopupMenu.background",         bg);
        UIManager.put("PopupMenu.border",             BorderFactory.createEmptyBorder());
        UIManager.put("Separator.background",         border);
        UIManager.put("Separator.foreground",         border);

        Font itemFont = jlshellSettings.getTerminalFont().deriveFont(Font.PLAIN, 12f);

        TerminalAction.buildMenu(provider, new com.jediterm.terminal.ui.TerminalActionMenuBuilder() {
            @Override
            public void addAction(TerminalAction action) {
                if (action == null || action.isHidden()) return;
                String rawName = action.getName();
                String label   = ACTION_KEY_MAP.containsKey(rawName)
                        ? i18n.apply(ACTION_KEY_MAP.get(rawName))
                        : rawName;
                JMenuItem item = new JMenuItem(label);
                item.setBackground(bg);
                item.setForeground(fg);
                item.setFont(itemFont);
                item.setOpaque(true);
                item.setEnabled(action.isEnabled(null));
                item.addActionListener(e -> action.actionPerformed(null));
                menu.add(item);
            }

            @Override
            public void addSeparator() {
                JSeparator sep = new JSeparator();
                sep.setBackground(border);
                sep.setForeground(border);
                menu.add(sep);
            }
        });

        return menu;
    }

    /** 在 color a 和 b 之间线性插值，ratio=0 返回 a，ratio=1 返回 b */
    private static Color blend(Color a, Color b, float ratio) {
        float r = 1f - ratio;
        return new Color(
                Math.round(a.getRed()   * r + b.getRed()   * ratio),
                Math.round(a.getGreen() * r + b.getGreen() * ratio),
                Math.round(a.getBlue()  * r + b.getBlue()  * ratio)
        );
    }

    /**
     * 拦截键盘事件，在分发给 TerminalKeyHandler 之前过滤噪音。
     *
     * macOS SwingNode 下的两类问题：
     *
     * 1. ESC：只产生 KEY_PRESSED/KEY_RELEASED，没有 KEY_TYPED。
     *    KEY_PRESSED(VK_ESCAPE) 的 keyChar='\0'（NUL），JediTerm 没有 ESC 的 keycode 映射，
     *    走到 isISOControl('\0')=true → processCharacter → 发送 NUL → 终端显示 ^@。
     *    修复：把 keyChar 替换为 '\u001B'，KEY_RELEASED 直接丢弃。
     *
     * 2. Command（⌘/Meta）组合键：同样只有 KEY_PRESSED，keyChar='\0'，
     *    JediTerm 把 NUL 发给终端 → 终端显示 ^@。
     *    修复：带 META 修饰符的 KEY_PRESSED/KEY_RELEASED 直接丢弃，不发给终端。
     *    （Cmd 组合键是 macOS 应用级快捷键，不应传入终端）
     */
    @Override
    public void processKeyEvent(KeyEvent e) {
        int id = e.getID();
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        // 修饰键单独按下/释放不发送给终端
        if (id != KeyEvent.KEY_TYPED && isModifierOnly(code)) {
            return;
        }
        // KEY_TYPED 里的 NUL/UNDEFINED 是修饰键副作用，过滤掉
        if (id == KeyEvent.KEY_TYPED && (c == KeyEvent.CHAR_UNDEFINED || c == '\0')) {
            return;
        }

        // macOS Command（⌘）组合键：带 META 修饰符的非 KEY_TYPED 事件不发给终端
        boolean hasMeta = (e.getModifiersEx() & KeyEvent.META_DOWN_MASK) != 0;
        if (hasMeta && id != KeyEvent.KEY_TYPED) {
            return;
        }

        // macOS SwingNode 下 ESC 的 KEY_PRESSED keyChar='\0'，需要修正为 '\u001B'
        if (id == KeyEvent.KEY_PRESSED && code == KeyEvent.VK_ESCAPE && c != '\u001B') {
            e = new KeyEvent(
                    (java.awt.Component) e.getSource(),
                    e.getID(), e.getWhen(), e.getModifiersEx(),
                    e.getKeyCode(), '\u001B', e.getKeyLocation()
            );
        }
        // ESC 的 KEY_RELEASED 不需要发送任何内容
        if (id == KeyEvent.KEY_RELEASED && code == KeyEvent.VK_ESCAPE) {
            return;
        }

        super.processKeyEvent(e);
    }

    private static boolean isModifierOnly(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META
                || keyCode == KeyEvent.VK_CAPS_LOCK
                || keyCode == KeyEvent.VK_NUM_LOCK
                || keyCode == KeyEvent.VK_SCROLL_LOCK;
    }
}
