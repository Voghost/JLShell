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

    private SwingTheme() {}

    public static void install(AppTheme theme, String accent, String family, int size) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Swing theme requires EDT");
        if (!registered) {
            FlatLaf.registerCustomDefaultsSource("com.jlshell.ui.swing.theme");
            registered = true;
        }
        // Windows/Linux 使用同一套主题化标题栏；macOS 保留系统窗口按钮。
        FlatLaf.setUseNativeWindowDecorations(true);
        if (!(theme == AppTheme.DARK ? FlatDarkLaf.setup() : FlatLightLaf.setup())) {
            throw new IllegalStateException("Unable to install Swing look and feel");
        }
        UIManager.put("Component.focusColor", Color.decode(accent));
        UIManager.put("TabbedPane.underlineColor", Color.decode(accent));
        Font base = UIManager.getFont("defaultFont");
        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource(
                family == null || family.isBlank() ? base.getFamily() : family, Font.PLAIN, size));
    }

    public static Color color(String key) {
        return UIManager.getColor("JLShell." + key);
    }

    public static void update(Component component) {
        // 保留 JediTerm 的自绘终端面板和滚动条 UI；不能让 LAF 遍历重装它们。
        if (component instanceof JediTermWidget || component instanceof javafx.embed.swing.JFXPanel) return;
        if (component instanceof JComponent jc) jc.updateUI();
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
