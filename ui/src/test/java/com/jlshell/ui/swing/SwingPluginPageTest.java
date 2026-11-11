package com.jlshell.ui.swing;

import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.model.WorkspacePage;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingPluginPageTest {
    @BeforeAll static void toolkit() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13)).get(10, TimeUnit.SECONDS);
    }

    @Test void retainsCustomCellsEditorAndActionsAcrossThemeChanges() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            AtomicInteger clicks = new AtomicInteger();
            var list = new JList<>(new String[]{"Loaded component"});
            list.setCellRenderer((values, item, index, selected, focus) -> new JLabel("Custom cell: " + item));
            var editor = new JTextPane(); editor.setText("Editor content");
            var button = new JButton("Plugin action"); button.addActionListener(event -> clicks.incrementAndGet());
            var content = new JPanel(new BorderLayout()); content.add(list, BorderLayout.NORTH);
            content.add(editor); content.add(button, BorderLayout.SOUTH);
            var tab = new WorkspacePage("Plugin"); tab.setContent(() -> content);
            var prepared = SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), null);
            var page = prepared.factory().get();
            JFrame frame = new JFrame(); frame.add(page); frame.setSize(480, 560); frame.setVisible(true);
            try {
                SwingTheme.install(AppTheme.LIGHT, "#3574f0", null, 13); SwingUtilities.updateComponentTreeUI(page);
                assertSame(content, editor.getParent());
                assertTrue(editor.getWidth() > 0 && editor.getHeight() > 0);
                assertEquals("Custom cell: Loaded component", ((JLabel) list.getCellRenderer().getListCellRendererComponent(list, "Loaded component", 0, false, false)).getText());
                button.doClick(); assertEquals(1, clicks.get());
            } finally { page.close(); frame.dispose(); }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void asynchronousArtworkKeepsItsOriginalIcon() throws Exception {
        var image = new CompletableFuture<java.awt.image.BufferedImage>();
        var artwork = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = artwork.createGraphics();
        try { graphics.setColor(Color.RED); graphics.fillRect(0, 0, 20, 20); } finally { graphics.dispose(); }
        var icon = PluginIcons.icon("Plugin", new PluginIcons.Source(image), 40, false);
        image.complete(artwork);
        SwingExecutors.runOnEdtAsync(() -> {
            var label = new JLabel(icon);
            var rendered = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var paint = rendered.createGraphics();
            try { icon.paintIcon(label, paint, 0, 0); } finally { paint.dispose(); }
            assertEquals(Color.RED.getRGB(), rendered.getRGB(20, 20));
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void nativeFactoryRunsAndClosesExactlyOnceOnEdt() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        var tab = new WorkspacePage("Plugin"); tab.setContent(() -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); return new NativePage(closes);
        });
        var prepared = SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), null);
        SwingExecutors.runOnEdtAsync(() -> {
            var page = prepared.factory().get(); page.close(); page.close(); assertEquals(1, closes.get());
        }).get(10, TimeUnit.SECONDS);
    }

    private static final class NativePage extends JPanel implements AutoCloseable {
        private final AtomicInteger closes;
        NativePage(AtomicInteger closes) { this.closes = closes; }
        @Override public void close() { assertTrue(SwingUtilities.isEventDispatchThread()); closes.incrementAndGet(); }
    }
}
