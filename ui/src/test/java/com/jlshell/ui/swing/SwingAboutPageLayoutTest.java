package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingAboutPageLayoutTest {
    @Test void fillsAvailableHeightAndKeepsOverflowAccessible() throws Exception {
        Supplier<JComponent> factory = () -> SwingAboutPage.create("0.2.2", new I18nService(Locale.SIMPLIFIED_CHINESE), () -> "environment", null);
        SwingUtilities.invokeAndWait(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
            JFrame frame = new JFrame();
            try {
                JScrollPane scroll = (JScrollPane) factory.get();
                frame.setContentPane(scroll);
                frame.setSize(1100, 850);
                frame.setVisible(true);
                frame.validate();
                assertFalse(scroll.getVerticalScrollBar().isVisible());
                Component footer = find(scroll, "about.footer");
                assertNotNull(footer);
                Point bottom = SwingUtilities.convertPoint(footer, 0, footer.getHeight(), scroll.getViewport());
                assertTrue(scroll.getViewport().getHeight() - bottom.y <= 30, "footer should be close to viewport bottom");
                frame.setSize(900, 640);
                frame.validate();
                assertFalse(scroll.getVerticalScrollBar().isVisible(), "minimum settings size should fit without scrolling");
                frame.setSize(700, 350);
                frame.validate();
                assertTrue(scroll.getVerticalScrollBar().isVisible(), "small windows must keep all content accessible");
            } finally { frame.dispose(); }
        });
    }
    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            Component result = find(child, name);
            if (result != null) return result;
        }
        return null;
    }
}
