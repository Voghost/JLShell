package com.jlshell.ui.swing.idea;

import javax.swing.*;
import java.awt.*;

/** Tooltip windows share the same transparent, rounded surface as the application's menus. */
public final class IdeaTooltipPopupFactory extends PopupFactory {
    private final PopupFactory delegate;
    public IdeaTooltipPopupFactory(PopupFactory delegate) { this.delegate = delegate instanceof IdeaTooltipPopupFactory previous ? previous.delegate : delegate; }

    @Override public Popup getPopup(Component owner, Component contents, int x, int y) {
        if (!(contents instanceof JToolTip tip)) return delegate.getPopup(owner, contents, x, y);
        GraphicsConfiguration configuration = owner == null ? null : owner.getGraphicsConfiguration();
        if (configuration == null || !configuration.getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
            return delegate.getPopup(owner, contents, x, y);
        Window parent = SwingUtilities.getWindowAncestor(owner);
        JWindow window = new JWindow(parent, configuration);
        window.setName("idea.tooltipWindow"); window.setType(Window.Type.POPUP);
        window.setFocusableWindowState(false); window.setBackground(new Color(0, true));
        window.getRootPane().putClientProperty("Window.shadow", false);
        tip.putClientProperty("JLShell.tooltipSurface", true);
        window.setContentPane(new IdeaPopupSurface(tip, true, true));
        window.pack();
        Rectangle screen = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        screen.x += insets.left; screen.y += insets.top;
        screen.width -= insets.left + insets.right; screen.height -= insets.top + insets.bottom;
        int margin = IdeaPopupSurface.SHADOW + 1;
        window.setLocation(Math.max(screen.x, Math.min(x - margin, screen.x + screen.width - window.getWidth())),
                Math.max(screen.y, Math.min(y - margin, screen.y + screen.height - window.getHeight())));
        return new Popup() {
            @Override public void show() { window.setVisible(true); }
            @Override public void hide() {
                window.dispose();
                tip.putClientProperty("JLShell.tooltipSurface", null);
                window.getContentPane().removeAll();
            }
        };
    }
}
