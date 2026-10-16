package com.jlshell.app;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.loader.PluginManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginPerformanceLoggingTest {
    @TempDir Path temporary;

    @Test void recordsPluginCodeFailureAndSuccessfulRetryWithDifferentTraceIds() {
        var logger = (Logger) LoggerFactory.getLogger("com.jlshell.performance");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var manager = new PluginManager(temporary.toString());
        var context = mock(PluginContext.class);
        var plugin = mock(JlShellPlugin.class);
        when(plugin.id()).thenReturn("example.plugin");
        try {
            var failure = new IllegalStateException("private plugin payload");
            doThrow(failure).doNothing().when(plugin).activate(context);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> manager.activateInstance(plugin, context)));
            manager.activateInstance(plugin, context);
            var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            var activationEnds = lines.stream().filter(line -> line.contains("operation=plugin-activate")
                    && line.contains("stage=total event=end")).toList();
            assertEquals(2, activationEnds.size());
            assertTrue(activationEnds.getFirst().contains("outcome=failed"));
            assertTrue(activationEnds.getLast().contains("outcome=success"));
            assertNotEquals(activationEnds.getFirst().split(" ")[1], activationEnds.getLast().split(" ")[1]);
            assertTrue(lines.stream().anyMatch(line -> line.contains("stage=plugin-code#")
                    && line.contains("event=end outcome=failed")));
            assertTrue(lines.stream().noneMatch(line -> line.contains("private plugin payload")));
        } finally {
            manager.deactivateAll();
            logger.detachAppender(appender); appender.stop();
        }
    }
}
