package com.jlshell.ui.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jlshell.ui.theme.AppTheme;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;

/** 统一 Swing 控件、HiDPI 和 IDEA New UI 的主题配置。仅在 EDT 使用。 */
public final class SwingTheme {
    private static boolean registered;
    private static AppTheme installedTheme;
    private static String installedAccent, installedFamily;
    private static int installedSize;

    public static boolean isCurrent(AppTheme theme, String accent, String family, int size) {
        return registered && theme == installedTheme && java.util.Objects.equals(accent, installedAccent)
                && java.util.Objects.toString(family, "").equals(installedFamily) && size == installedSize;
    }

    private SwingTheme() {}

    public static void install(AppTheme theme, String accent, String family, int size) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Swing theme requires EDT");
        if (isCurrent(theme, accent, family, size)) return;
        Font previous = UIManager.getFont("defaultFont");
        if (!registered) {
            FlatLaf.registerCustomDefaultsSource("com.jlshell.ui.swing.theme");
            registered = true;
        }
        // Windows/Linux 使用同一套主题化标题栏；macOS 保留系统窗口按钮。
        if (com.formdev.flatlaf.util.SystemInfo.isMacOS) System.setProperty("apple.laf.useScreenMenuBar", "true");
        FlatLaf.setUseNativeWindowDecorations(true);
        if (!(theme == AppTheme.DARK ? FlatDarkLaf.setup() : FlatLightLaf.setup())) {
            throw new IllegalStateException("Unable to install Swing look and feel");
        }
        javax.swing.PopupFactory.setSharedInstance(new com.jlshell.ui.swing.idea.IdeaTooltipPopupFactory(
                javax.swing.PopupFactory.getSharedInstance()));
        // Match IDEA's 1.2s initial tooltip delay and 4s dismiss window.
        javax.swing.ToolTipManager toolTips = javax.swing.ToolTipManager.sharedInstance();
        toolTips.setInitialDelay(1200);
        toolTips.setDismissDelay(4000);
        toolTips.setReshowDelay(500);
        UIManager.put("Component.focusColor", Color.decode(accent));
        UIManager.put("TabbedPane.underlineColor", Color.decode(accent));
        Font base = UIManager.getFont("defaultFont");
        // 保留 FlatLaf 的系统字体与 Unicode 复合字体回退；重新按 family 构造物理字体会丢失 CJK 回退。
        Font uiFont = family == null || family.isBlank() || "System".equalsIgnoreCase(family)
                ? base.deriveFont(Font.PLAIN, (float) size)
                : com.formdev.flatlaf.util.FontUtils.getCompositeFont(family, Font.PLAIN, size);
        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(uiFont));
        // FlatLaf 的各控件字体可能已解析缓存，不能只更新 defaultFont 后期待它们自动派生。
        Font selectedFont = UIManager.getFont("defaultFont");
        var fontKeys = UIManager.getDefaults().keySet().stream()
                .filter(key -> key instanceof String name && name.toLowerCase(java.util.Locale.ROOT).endsWith("font"))
                .toList();
        for (Object key : fontKeys) {
            Object value = UIManager.get(key);
            if (value instanceof Font font) UIManager.put(key, new javax.swing.plaf.FontUIResource(selectedFont.deriveFont(font.getStyle())));
        }
        UIManager.put("JLShell.previousFont", previous == null ? base : previous);
        installedTheme = theme; installedAccent = accent;
        installedFamily = java.util.Objects.toString(family, ""); installedSize = size;
    }

    public static Color color(String key) {
        return UIManager.getColor("JLShell." + key);
    }

    /** Paint from the current palette instead of freezing the color when a dialog is built. */
    static javax.swing.border.Border border(int top, int left, int bottom, int right) {
        return new javax.swing.border.AbstractBorder() {
            public java.awt.Insets getBorderInsets(Component component) { return new java.awt.Insets(top, left, bottom, right); }
            public java.awt.Insets getBorderInsets(Component component, java.awt.Insets insets) {
                insets.set(top, left, bottom, right); return insets;
            }
            public void paintBorder(Component component, java.awt.Graphics graphics, int x, int y, int width, int height) {
                java.awt.Graphics g = graphics.create();
                try {
                    g.setColor(color("border"));
                    if (top > 0) g.fillRect(x, y, width, top);
                    if (left > 0) g.fillRect(x, y, left, height);
                    if (bottom > 0) g.fillRect(x, y + height - bottom, width, bottom);
                    if (right > 0) g.fillRect(x + width - right, y, right, height);
                } finally { g.dispose(); }
            }
        };
    }

    public static void update(Component component) {
        // 保留 JediTerm 的自绘终端面板和滚动条 UI；不能让 LAF 遍历重装它们。
        if (component instanceof JediTermWidget || component instanceof javafx.embed.swing.JFXPanel) return;
        if (component instanceof JComponent jc) {
            Font original = jc.getFont(), previous = UIManager.getFont("JLShell.previousFont");
            if (jc.getClientProperty("jlshell.fontScale") == null && original != null
                    && !(original instanceof javax.swing.plaf.UIResource)) {
                jc.putClientProperty("jlshell.fontScale", original.getSize2D() / Math.max(1, previous.getSize2D()));
                jc.putClientProperty("jlshell.fontStyle", original.getStyle());
            }
            jc.updateUI();
            if (Boolean.TRUE.equals(jc.getClientProperty("jlshell.ideaForm"))) IdeaFormStyle.apply(jc);
            if (Boolean.TRUE.equals(jc.getClientProperty("JLShell.primaryAction"))) {
                jc.setBackground(UIManager.getColor("Component.focusColor")); jc.setForeground(Color.WHITE);
            }
            if (Boolean.TRUE.equals(jc.getClientProperty("JLShell.secondaryForeground"))) {
                jc.setForeground(color("secondary"));
            }
            if (jc.getClientProperty("jlshell.fontScale") instanceof Float scale) {
                Font base = UIManager.getFont("defaultFont");
                jc.setFont(base.deriveFont((Integer) jc.getClientProperty("jlshell.fontStyle"), base.getSize2D() * scale));
            }
        }
        // TreeCellRenderer 不在组件子树中，其缓存颜色也必须随主题更新。
        if (component instanceof javax.swing.JTree tree && tree.getCellRenderer() instanceof Component renderer) {
            update(renderer);
        }
        if (component instanceof javax.swing.JMenu menu) update(menu.getPopupMenu());
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) update(child);
        }
    }
}
