package com.jlshell.ui.swing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.support.PluginOpenTiming;
import com.jlshell.ui.view.PluginsTabView;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class PluginOpenTimingSmokeTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }

    @Test void followsFxAndEdtQueuesThroughFactoryAndLogsOnlyOneFirstPaint() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("com.jlshell.performance");
        var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start(); logger.addAppender(appender);
        JFrame[] frame = new JFrame[1];
        SwingPluginPage[] page = new SwingPluginPage[1];
        try {
            var timing = new PluginOpenTiming("example.native");
            var prepared = FxThread.supplyAsync(() -> {
                timing.mark("fx-dispatch");
                timing.mark("swing-view-request");
                Tab tab = new Tab("Plugin", new Pane());
                tab.getProperties().put(PluginOpenTiming.PROPERTY, timing);
                tab.getProperties().put(PluginsTabView.NATIVE_VIEW, (Supplier<JComponent>) () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return new JLabel("Plugin content");
                });
                return SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), null);
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                page[0] = prepared.factory().get();
                frame[0] = new JFrame("Timing test");
                frame[0].add(page[0]); frame[0].setSize(360, 240); frame[0].setVisible(true);
            }).get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && lines(appender).stream()
                    .noneMatch(line -> line.contains("outcome=swing-first-paint"))) Thread.sleep(20);
            SwingExecutors.runOnEdtAsync(() -> { page[0].repaint(); page[0].close(); frame[0].dispose(); })
                    .get(10, TimeUnit.SECONDS);
            var messages = lines(appender);
            assertEquals(1, messages.stream().filter(line -> line.contains("stage=total event=end")).count());
            assertTrue(messages.stream().anyMatch(line -> line.contains("outcome=swing-first-paint")));
            for (String stage : List.of("fx-queue#", "edt-page-queue#", "swing-view-factory#")) {
                assertTrue(messages.stream().anyMatch(line -> line.contains("stage=" + stage)
                        && line.contains("event=end outcome=success")), stage);
            }
            assertEquals(1, messages.stream().map(line -> line.split(" ")[1]).distinct().count());
        } finally {
            SwingExecutors.runOnEdtAsync(() -> {
                if (page[0] != null) page[0].close();
                if (frame[0] != null) frame[0].dispose();
            }).get(10, TimeUnit.SECONDS);
            logger.detachAppender(appender); appender.stop();
        }
    }

    private static List<String> lines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test void legacyFxContentLogsLayoutSeparatelyFromSwingContainerPainting() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("com.jlshell.performance");
        var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start(); logger.addAppender(appender);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        JFrame[] frame = new JFrame[1];
        SwingPluginPage[] page = new SwingPluginPage[1];
        try {
            var timing = new PluginOpenTiming("example.legacy");
            var prepared = FxThread.supplyAsync(() -> {
                var settings = new com.jlshell.core.service.AppSettingsService() {
                    public java.util.Optional<String> get(String key) { return java.util.Optional.empty(); }
                    public void set(String key, String value) {}
                    public void remove(String key) {}
                };
                var schemes = new com.jlshell.terminal.service.ColorSchemeRegistry(null, null) {
                    @Override public java.util.Optional<com.jlshell.terminal.model.TerminalColorScheme> findByName(String name) {
                        return java.util.Optional.empty();
                    }
                };
                var themes = new com.jlshell.ui.theme.ThemeService(settings, schemes, executor);
                timing.mark("fx-dispatch"); timing.mark("fx-view-request");
                Tab tab = new Tab("Legacy plugin", new javafx.scene.layout.BorderPane(new javafx.scene.control.Label("Content")));
                tab.getProperties().put(PluginOpenTiming.PROPERTY, timing);
                return SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), themes);
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                page[0] = prepared.factory().get();
                frame[0] = new JFrame("Legacy timing test");
                frame[0].add(page[0]); frame[0].setSize(360, 240); frame[0].setVisible(true);
            }).get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && lines(appender).stream()
                    .noneMatch(line -> line.contains("outcome=fx-content-laid-out"))) Thread.sleep(20);
            var messages = lines(appender);
            assertTrue(messages.stream().anyMatch(line -> line.contains("outcome=fx-content-laid-out")), messages.toString());
            assertTrue(messages.stream().anyMatch(line -> line.contains("stage=fx-scene-create#")
                    && line.contains("event=end outcome=success")));
            assertFalse(messages.stream().anyMatch(line -> line.contains("outcome=swing-first-paint")));
        } finally {
            SwingExecutors.runOnEdtAsync(() -> {
                if (page[0] != null) page[0].close();
                if (frame[0] != null) frame[0].dispose();
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
            logger.detachAppender(appender); appender.stop();
        }
    }
}
