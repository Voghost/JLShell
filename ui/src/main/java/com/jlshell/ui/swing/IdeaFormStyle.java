package com.jlshell.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;

/** Form-specific style, based on IDEA Islands control tokens and DarculaNewUIUtil border geometry. */
final class IdeaFormStyle {
    private IdeaFormStyle() {}

    static void apply(JComponent component) {
        // Scroll panes may deliberately have EmptyBorder; their border does not accept FlatBorder style keys.
        if (component instanceof JScrollPane) return;
        if(component instanceof JComboBox<?> combo&&!(combo.getUI() instanceof com.jlshell.ui.swing.idea.IdeaComboBoxUI))
            combo.setUI(new com.jlshell.ui.swing.idea.IdeaComboBoxUI());
        component.putClientProperty("jlshell.ideaForm", true);
        Color background = UIManager.getColor("JLShell.formBackground");
        Color raised = UIManager.getColor("JLShell.formRaised");
        Color border = UIManager.getColor("JLShell.formBorder");
        Color muted = UIManager.getColor("JLShell.formDisabledBorder");
        Color accent = UIManager.getColor("Component.focusColor");
        if (component instanceof JCheckBox) {
            component.putClientProperty(FlatClientProperties.STYLE, Map.of(
                    "icon.selectedBackground", accent, "icon.selectedBorderColor", accent,
                    "icon.checkmarkColor", Color.WHITE, "icon.focusColor", accent, "icon.focusWidth", 1f));
            return;
        }
        if (component instanceof JTabbedPane tabs) {
            tabs.setUI(new FormTabsUI());
            tabs.putClientProperty(FlatClientProperties.STYLE,
                    "tabHeight: 34; tabInsets: 0,12,0,12; tabAreaInsets: 4,12,4,12; contentSeparatorHeight: 1; showTabSeparators: false");
            tabs.setBackground(background);
            return;
        }
        // JEditorPane also uses FlatEditorPaneUI, which inherits FlatTextAreaUI.
        // Text-area/editor-pane UIs do not support component border style keys
        // such as borderColor; passing them is logged as UnknownStyleException
        // whenever the look and feel reinstalls the UI (for example on theme change).
        if(component instanceof JTextArea||component instanceof JTextPane||component instanceof JEditorPane) {
            component.putClientProperty(FlatClientProperties.STYLE,Map.of("background",background,"inactiveBackground",background));
            return;
        }
        Map<String,Object> style = new HashMap<>();
        style.put("arc", 8); style.put("focusWidth", 1); style.put("innerFocusWidth", 0f);
        style.put("borderWidth", 1f); style.put("borderColor", border);
        style.put("disabledBorderColor", muted); style.put("focusedBorderColor", accent); style.put("focusColor", accent);
        if (component instanceof JButton button) {
            boolean link = "borderless".equals(button.getClientProperty("JButton.buttonType"));
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("jlshell.primary"));
            style.put("background", background); style.put("hoverBackground", raised);
            style.put("pressedBackground", UIManager.getColor("JLShell.formPressed"));
            style.put("hoverBorderColor", UIManager.getColor("JLShell.formHoverBorder"));
            style.put("pressedBorderColor", border); style.put("minimumWidth", link ? 0 : 72);
            style.put("margin", new Insets(3, link ? 2 : 12, 3, link ? 2 : 12));
            style.put("default.background", accent); style.put("default.borderColor", accent);
            style.put("default.focusedBorderColor", accent); style.put("default.focusColor", accent);
            style.put("default.hoverBackground", accent.brighter()); style.put("default.pressedBackground", accent.darker());
            button.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
            if (primary) {
                style.put("background", accent); style.put("foreground", Color.WHITE);
                style.put("borderColor", accent); style.put("hoverBackground", accent.brighter());
                style.put("pressedBackground", accent.darker());
            }
            if (link) style.put("foreground", UIManager.getColor("JLShell.formLink"));
        } else if (component instanceof JSpinner) {
            style.put("background", background); style.put("buttonBackground", background);
        } else if (component instanceof JComboBox<?>) {
            style.put("background", raised); style.put("buttonBackground", raised);
            style.put("buttonStyle", "none"); style.put("padding", new Insets(2, 8, 2, 8));
            style.put("popupBackground",SwingTheme.color("popup"));
        } else if (component instanceof JTextComponent text) {
            style.put("background", background); style.put("inactiveBackground", background);
            text.setMargin(new Insets(3, 8, 3, 8));
        } else if (!(component instanceof JScrollPane)) return;
        component.putClientProperty(FlatClientProperties.STYLE, style);
    }

    /** Rounded selected tab with its own outline, as in IDEA datasource configuration. */
    private static final class FormTabsUI extends FlatTabbedPaneUI {
        @Override protected void paintTabBackground(Graphics graphics, int placement, int index,
                                                     int x, int y, int width, int height, boolean selected) {
            if (!selected && index != getRolloverTab()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float inset = UIScale.scale(2f), arc = UIScale.scale(8f);
                Shape outline = new RoundRectangle2D.Float(x + inset + .5f, y + inset + .5f,
                        width - inset * 2 - 1, height - inset * 2 - 1, arc, arc);
                g.setColor(UIManager.getColor(selected ? "JLShell.tabSelection" : "JLShell.formRaised")); g.fill(outline);
                if (selected) { g.setColor(UIManager.getColor("JLShell.tabBorder")); g.setStroke(new BasicStroke(UIScale.scale(1f))); g.draw(outline); }
            } finally { g.dispose(); }
        }
        @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {}
        @Override protected void paintTabSelection(Graphics g, int placement, int index, int x, int y, int w, int h) {}
    }
}
