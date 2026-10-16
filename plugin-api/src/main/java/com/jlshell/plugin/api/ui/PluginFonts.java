package com.jlshell.plugin.api.ui;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.function.Consumer;

/** Read-only host preferences shared by Session and Program plugins.
 * Does not describe a plugin's local font overrides or temporary terminal zoom. */
public interface PluginFonts {
    boolean available();

    /** Current terminal font preference. Safe to read from any thread; no I/O. */
    FontSpec terminalFont();

    /** Current application UI font preference. Safe to read from any thread; no I/O. */
    FontSpec uiFont();

    /** Delivers the current value first, followed by distinct changes in order.
     * Callbacks are asynchronous; dispatch UI changes to the appropriate toolkit thread.
     * Close the handle when a view is removed. The host also closes it when the context stops. */
    Registration onTerminalFontChanged(Consumer<FontSpec> listener);

    /** Same delivery and lifecycle contract as {@link #onTerminalFontChanged(Consumer)}. */
    Registration onUiFontChanged(Consumer<FontSpec> listener);

    static PluginFonts unavailable() { return UnavailablePluginFonts.INSTANCE; }
}

final class UnavailablePluginFonts implements PluginFonts {
    static final UnavailablePluginFonts INSTANCE = new UnavailablePluginFonts();
    private UnavailablePluginFonts() {}
    public boolean available() { return false; }
    public FontSpec terminalFont() { throw new UnsupportedOperationException("Host font API unavailable"); }
    public FontSpec uiFont() { throw new UnsupportedOperationException("Host font API unavailable"); }
    public Registration onTerminalFontChanged(Consumer<FontSpec> listener) { return Registration.noop(); }
    public Registration onUiFontChanged(Consumer<FontSpec> listener) { return Registration.noop(); }
}
