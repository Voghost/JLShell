package com.jlshell.app;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class UncaughtExceptionReporterTest {
    @Test void preservesOriginalFailureWhenLoggingItselfCannotLoadClasses() {
        var output = new ByteArrayOutputStream();
        var reporter = new UncaughtExceptionReporter(new PrintStream(output, true, StandardCharsets.UTF_8),
                (thread, error) -> { throw new NoClassDefFoundError("ch/qos/logback/classic/spi/ThrowableProxy"); });
        assertDoesNotThrow(() -> reporter.uncaughtException(new Thread("AWT-EventQueue-0"),
                new NoClassDefFoundError("com/sun/javafx/embed/swing/SwingEvents")));
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("com/sun/javafx/embed/swing/SwingEvents"));
        assertTrue(text.contains("original exception was printed above"));
        assertTrue(text.contains("ch/qos/logback/classic/spi/ThrowableProxy"));
    }
}
