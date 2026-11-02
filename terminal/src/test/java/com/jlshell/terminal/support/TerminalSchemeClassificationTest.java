package com.jlshell.terminal.support;

import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.BuiltInColorSchemeLoader;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalSchemeClassificationTest {
    @Test void defaultSchemesAreClassifiedAndRestorableFromThePicker() {
        var registry = new ColorSchemeRegistry(new BuiltInColorSchemeLoader(), null);
        assertTrue(TerminalColorScheme.light().isLight());
        assertFalse(TerminalColorScheme.dark().isLight());
        assertTrue(registry.findByName(TerminalColorScheme.light().name()).orElseThrow().isLight());
        assertFalse(registry.findByName(TerminalColorScheme.dark().name()).orElseThrow().isLight());
        assertTrue(registry.isBuiltIn(TerminalColorScheme.light().name()));
    }
}
