package com.jlshell.plugin.api.ui;

/** Common optional capability, shared to avoid conflicting defaults in dual-context implementations. */
public interface FontAwareContext {
    /** Read and follow host font preferences; check available() before use on older hosts. */
    default PluginFonts fonts() { return PluginFonts.unavailable(); }
}
