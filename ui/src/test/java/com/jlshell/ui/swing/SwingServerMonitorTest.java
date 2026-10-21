package com.jlshell.ui.swing;

import com.jlshell.ui.model.ServerMonitorSnapshot;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SwingServerMonitorTest {
    private static ServerMonitorSnapshot sample(long tick, String failure) {
        return new ServerMonitorSnapshot(tick, "srv-production", "Debian GNU/Linux 13", "Linux 6.12", "192.0.2.19", "eth0", "17:06:42 CST",
                new ServerMonitorSnapshot.Resource("32%", "8 cores · Load 1.84", 20 + tick % 30, 32),
                new ServerMonitorSnapshot.Resource("24%", "3.1 GB / 12.6 GB", 24 + tick % 5, 24),
                new ServerMonitorSnapshot.Resource("↓ 1.3 MB/s", "↑ 421 KB/s", 40000 + (tick % 6) * 3000, -1),
                new ServerMonitorSnapshot.Resource("23%", "24 GB / 113 GB", 23, 23), failure);
    }
    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            Component result = named(child, name); if (result != null) return result;
        }
        return null;
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child); }
    private static void screenshot(SwingServerMonitor view, String file, int width, int height) throws Exception {
        view.setSize(width, height); layout(view);
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2); view.printAll(graphics); graphics.dispose();
        Path target = Path.of("target", "review", file + ".png"); Files.createDirectories(target.getParent());
        javax.imageio.ImageIO.write(image, "png", target.toFile());
    }
    private static final class NativeSettingsProbeForMonitor {
        static java.util.List<Component> labels(Component root) {
            java.util.List<Component> result = new java.util.ArrayList<>();
            if (root instanceof JLabel) result.add(root);
            if (root instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(labels(child));
            return result;
        }
        static java.util.List<JScrollPane> scrolls(Component root) {
            java.util.List<JScrollPane> result = new java.util.ArrayList<>();
            if (root instanceof JScrollPane scroll) result.add(scroll);
            if (root instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(scrolls(child));
            return result;
        }
    }
    @Test void processRowsRetainCommandNamesAndRejectHeaders() {
        var rows = SwingServerMonitor.parseProcesses("PID USER CPU MEM RSS CMD\n 42 root 12.5 3.1 1024 /usr/bin/java app name\ninvalid\n");
        assertEquals(1, rows.size());
        assertEquals("42", rows.getFirst()[0]);
        assertEquals("/usr/bin/java app name", rows.getFirst()[5]);
    }
    @Test void nativeLayoutUpdatesWithoutGrowingAndAdaptsToWidthAndTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingServerMonitor view = null;
            try {
                SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
                view = new SwingServerMonitor(new I18nService(Locale.SIMPLIFIED_CHINESE), type -> CompletableFuture.completedFuture("fixture"));
                for (int i = 1; i <= 70; i++) view.accept(sample(i, null));
                screenshot(view, "server-monitor-dark", 1150, 205);
                assertEquals(named(view, "monitor.cpu").getY(), named(view, "monitor.disk").getY());
                assertTrue(named(view, "monitor.cpu").getHeight() >= 110);
                screenshot(view, "server-monitor-narrow-dark", 600, 330);
                assertTrue(named(view, "monitor.ip").getY() > named(view, "monitor.cpu").getY());
                screenshot(view, "server-monitor-minimum-dark", 1150, 64);
                for (String metric : new String[]{"cpu", "mem", "ip", "disk"}) {
                    JComponent tile = (JComponent) named(view, "monitor." + metric);
                    assertEquals(false, tile.getClientProperty("monitor.chartsVisible"), "Minimum height must hide charts");
                    for (Component child : NativeSettingsProbeForMonitor.labels(tile))
                        assertTrue(child.getHeight() >= child.getFontMetrics(child.getFont()).getHeight(), "Compact text must be fully visible");
                    var inlineLabels = NativeSettingsProbeForMonitor.labels(tile);
                    assertEquals(3, inlineLabels.size());
                    assertTrue(inlineLabels.getFirst().getFont().isBold(), "Metric title must be bold");
                    assertFalse(inlineLabels.get(1).getFont().isBold(), "Metric value must use regular weight");
                    assertFalse(inlineLabels.get(2).getFont().isBold(), "Metric detail must use regular weight");
                    int center = inlineLabels.getFirst().getY() + inlineLabels.getFirst().getHeight() / 2;
                    for (Component label : inlineLabels)
                        assertTrue(Math.abs(center - label.getY() - label.getHeight() / 2) <= 1, "Compact metrics must share a single row");
                }
                assertTrue(NativeSettingsProbeForMonitor.scrolls(view).stream().noneMatch(s -> s.getVerticalScrollBar().isVisible()), "Wide minimum-height monitor must not need scrolling");
                JLabel info = (JLabel) named(view, "monitor.info");
                assertNotNull(info.getIcon());
                assertTrue(info.getToolTipText().contains("每 3 秒更新"));
                assertEquals("17:06:42 CST", ((JLabel) named(view, "monitor.state")).getText());
                screenshot(view, "server-monitor-default-dark", 1150, 138);
                assertEquals(true, ((JComponent) named(view, "monitor.cpu")).getClientProperty("monitor.chartsVisible"));
                screenshot(view, "server-monitor-compact-dark", 1150, 80);
                SwingTheme.install(AppTheme.LIGHT, "#3574f0", "SansSerif", 13); SwingTheme.update(view);
                screenshot(view, "server-monitor-light", 1150, 205);
                assertEquals(UIManager.getColor("Panel.background"), view.getBackground());
                view.accept(sample(70, "Timeout"));
                assertTrue(((JLabel) named(view, "monitor.state")).getText().contains("更新失败"));
                view.disconnected();
                assertFalse(named(view, "monitor.cpu").isEnabled());
                assertTrue(((JLabel) named(view, "monitor.state")).getText().contains("已断开"));
                view.close();
                view.accept(sample(72, null));
                assertTrue(((JLabel) named(view, "monitor.state")).getText().contains("已断开"));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { if (view != null) view.close(); }
        });
    }
}
