package com.jlshell.ui.swing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.UiThread;
import com.jlshell.ui.support.PluginOpenTiming;
import com.jlshell.ui.view.PluginsTabView;
import com.jlshell.ui.model.WorkspacePage;
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
            var prepared = UiThread.supplyAsync(() -> {
                timing.mark("edt-dispatch");
                timing.mark("swing-view-request");
                WorkspacePage tab = new WorkspacePage("Plugin");
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
            for (String stage : List.of("ui-dispatch-queue#", "edt-page-queue#", "swing-view-factory#")) {
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

}
