package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.jediterm.core.TerminalCoordinates;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;

/**
 * 暴露受保护的字体/布局刷新能力。
 * 过滤 macOS SwingNode 下修饰键单独触发的 keyChar=\0 噪音事件。
 * 覆盖右键菜单，使其与应用主题颜色一致并支持多语言。
 */
public class RefreshableTerminalPanel extends TerminalPanel {

    // JediTerm 硬编码的英文菜单项名 → i18n key 映射
    private static final Map<String, String> ACTION_KEY_MAP = Map.ofEntries(
            Map.entry("Copy",         "terminal.action.copy"),
            Map.entry("Paste",        "terminal.action.paste"),
            Map.entry("Clear Buffer", "terminal.action.clearBuffer"),
            Map.entry("Select All",   "terminal.action.selectAll"),
            Map.entry("Find",         "terminal.action.find"),
            Map.entry("Page Up",      "terminal.action.pageUp"),
            Map.entry("Page Down",    "terminal.action.pageDown"),
            Map.entry("Line Up",      "terminal.action.lineUp"),
            Map.entry("Line Down",    "terminal.action.lineDown")
    );

    private final JlshellSettingsProvider jlshellSettings;
    private final Function<String, String> i18n;

    /**
     * JediTermWidget 在 init 时通过 setCoordAccessor 注入 TerminalCoordinates，
     * 这里捕获引用用于 IME 候选窗定位（见 getCursorLocationInComponent）。
     */
    private volatile TerminalCoordinates capturedCoordAccessor;

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
    public void setCoordAccessor(TerminalCoordinates coordAccessor) {
        this.capturedCoordAccessor = coordAccessor;
        super.setCoordAccessor(coordAccessor);
    }

    /**
     * 返回终端光标在 Swing 组件内的像素坐标（用于 IME 候选窗定位）。
     * 复刻 JediTerm MyInputMethodRequests.getTextLocation 的算法：
     *   x = cursorX * charWidth + insetX
     *   y = (cursorY + 1) * charHeight  （+1 让候选窗落在光标行下方）
     * 光标坐标通过 capturedCoordAccessor 获取（JediTermWidget init 时注入）。
     * 未初始化时返回 (0,0)，候选窗退化为组件左上角。
     */
    public Point getCursorLocationInComponent() {
        TerminalCoordinates coord = capturedCoordAccessor;
        if (coord == null || myCharSize.width <= 0 || myCharSize.height <= 0) {
            return new Point(0, 0);
        }
        int x = coord.getX() * myCharSize.width + getInsetX();
        int y = (coord.getY() + 1) * myCharSize.height;
        return new Point(x, y);
    }

    @Override
    protected JPopupMenu createPopupMenu(TerminalActionProvider provider) {
        Color bg      = jlshellSettings.backgroundColor();
        Color fg      = jlshellSettings.foregroundColor();
        Color hover   = blend(bg, fg, 0.12f);
        Color border  = blend(bg, fg, 0.22f);

        Font itemFont = jlshellSettings.getTerminalFont().deriveFont(Font.PLAIN, 12f);
        // Use a system font that can render CJK for the popup menu items.
        // Terminal monospace fonts (Consolas, etc.) often lack CJK glyphs on Windows.
        final Font menuFont = (!itemFont.canDisplay('复') || !itemFont.canDisplay('制'))
                ? new Font(Font.DIALOG, Font.PLAIN, 12) : itemFont;

        JPopupMenu menu = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }

            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }

            @Override
            public void setVisible(boolean b) {
                if (b) {
                    SwingUtilities.invokeLater(() -> {
                        Window w = SwingUtilities.getWindowAncestor(this);
                        if (w != null) {
                            w.setBackground(new Color(0, 0, 0, 0));
                        }
                    });
                }
                super.setVisible(b);
            }
        };
        menu.setBackground(bg);
        menu.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        menu.setOpaque(false);

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
                item.setFont(menuFont);
                item.setOpaque(true);
                item.setEnabled(action.isEnabled(null));
                item.addActionListener(e -> action.actionPerformed(null));
                item.setBorder(BorderFactory.createEmptyBorder(5, 16, 5, 16));
                // 去掉默认的选中边框和虚线
                item.setBorderPainted(false);
                item.setFocusPainted(false);
                // Hover highlight
                item.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                        if (item.isEnabled()) item.setBackground(hover);
                    }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) {
                        item.setBackground(bg);
                    }
                });
                menu.add(item);
            }

            @Override
            public void addSeparator() {
                // 自绘分隔线：一条细线，左右留 8px 边距
                javax.swing.JPanel sepPanel = new javax.swing.JPanel(null) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setColor(border);
                        g2.drawLine(8, getHeight() / 2, getWidth() - 8, getHeight() / 2);
                        g2.dispose();
                    }
                };
                sepPanel.setOpaque(false);
                sepPanel.setPreferredSize(new java.awt.Dimension(1, 9));
                menu.add(sepPanel);
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
        // KEY_TYPED 里的 NUL/UNDEFINED 是修饰键副作用或 IME composing 噪音，过滤掉
        if (id == KeyEvent.KEY_TYPED && (c == KeyEvent.CHAR_UNDEFINED || c == '\0')) {
            return;
        }
        // Windows IME composing 期间产生的 KEY_PRESSED keyChar='\0' 且 keyCode=0，
        // JediTerm 会当作 isISOControl('\0') → processCharacter → 发送 NUL → 显示 ^@。
        // 正常功能键（Backspace、方向键、Ctrl+C 等）keyCode 有明确值，不受此过滤影响。
        if (isWindows() && id == KeyEvent.KEY_PRESSED && c == '\0' && code == KeyEvent.VK_UNDEFINED) {
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

        // Windows SwingNode forwards Ctrl+letter KEY_PRESSED with a wrong keyChar
        // ('\0' or 'U' from JavaFX "Undefined" string), but the keyCode (VK_A..VK_Z)
        // is correct. JediTerm's TerminalKeyEncoder has no Ctrl+letter entries, so
        // it falls into the isISOControl(keyChar) branch and sends the wrong byte —
        // Ctrl+C/D etc. either do nothing or send NUL.
        // Fix: when Ctrl is held (no Alt/Meta) and keyCode is A-Z, rewrite keyChar
        // to the matching control character (Ctrl+A= ... Ctrl+Z=) so
        // JediTerm's processCharacter sends the right byte.
        if (id == KeyEvent.KEY_PRESSED
                && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0
                && (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) == 0
                && (e.getModifiersEx() & KeyEvent.META_DOWN_MASK) == 0) {
            char controlChar = ctrlToControlChar(code);
            if (controlChar != '\0' && c != controlChar) {
                e = new KeyEvent(
                        (java.awt.Component) e.getSource(),
                        e.getID(), e.getWhen(), e.getModifiersEx(),
                        e.getKeyCode(), controlChar, e.getKeyLocation()
                );
            }
        }

        super.processKeyEvent(e);
    }

    /**
     * Map A-Z keyCode to the control char produced by Ctrl+A..Ctrl+Z
     * (Ctrl+A= ... Ctrl+Z=). Non A-Z returns '\0'.
     */
    private static char ctrlToControlChar(int keyCode) {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            return (char) (keyCode - KeyEvent.VK_A + 1);
        }
        return '\0';
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

    /**
     * CJK 字体回退：当终端主字体无法渲染中文字符时，
     * 自动切换到系统 CJK 字体，避免显示方框。
     * <ul>
     *   <li>Windows → "Microsoft YaHei"</li>
     *   <li>macOS   → "PingFang SC"</li>
     *   <li>Linux   → "Noto Sans CJK SC" / "WenQuanYi Micro Hei"</li>
     * </ul>
     */
    @Override
    protected @NotNull Font getFontToDisplay(char[] text, int start, int end, @NotNull TextStyle style) {
        Font baseFont = super.getFontToDisplay(text, start, end, style);
        for (int i = start; i < end; i++) {
            if (text[i] != CharUtils.DWC && !baseFont.canDisplay(text[i])) {
                return new Font(cjkFallbackFamily(), baseFont.getStyle(), baseFont.getSize());
            }
        }
        return baseFont;
    }

    /** 返回当前平台的 CJK 回退字体族名，惰性计算一次后缓存 */
    private static String cjkFallbackFamily() {
        if (cjkFallback != null) return cjkFallback;
        String os = System.getProperty("os.name", "").toLowerCase();
        String family;
        if (os.contains("win")) {
            family = "Microsoft YaHei";
        } else if (os.contains("mac")) {
            family = "PingFang SC";
        } else {
            // Linux: 尝试常见 CJK 字体
            family = firstAvailable("Noto Sans CJK SC", "WenQuanYi Micro Hei", "Droid Sans Fallback");
        }
        cjkFallback = family;
        return family;
    }

    /** 从候选列表中返回第一个 AWT 可识别的字体族名，都找不到则回退到 Dialog */
    private static String firstAvailable(String... candidates) {
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available = java.util.Set.of(ge.getAvailableFontFamilyNames());
        for (String c : candidates) {
            if (available.contains(c)) return c;
        }
        return Font.DIALOG;
    }

    private static volatile String cjkFallback;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
