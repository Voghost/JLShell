package com.jlshell.plugin.api.ui;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.ProgramPluginContext;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PluginFontsTest {
    @Test void defaultsAreSharedAndDoNotInventHostFontValues() throws Exception {
        assertSame(PluginContext.class.getMethod("fonts").getDeclaringClass(),
                ProgramPluginContext.class.getMethod("fonts").getDeclaringClass());
        FontAwareContext oldContext = new FontAwareContext() {};
        assertFalse(oldContext.fonts().available());
        assertThrows(UnsupportedOperationException.class, () -> oldContext.fonts().terminalFont());
        assertThrows(UnsupportedOperationException.class, () -> oldContext.fonts().uiFont());
        AtomicBoolean called = new AtomicBoolean();
        var subscription = oldContext.fonts().onUiFontChanged(font -> called.set(true));
        subscription.close(); subscription.close(); assertFalse(called.get());
    }

    @Test void neutralFontModelRepresentsSystemFallbackAndRejectsInvalidSizes() {
        assertTrue(new FontSpec(null, 13).systemDefault());
        assertFalse(new FontSpec("Monospaced", 14.5).systemDefault());
        for (double size : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new FontSpec("Test", size));
    }
}
