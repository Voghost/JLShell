package com.jlshell.plugin.api.ui;

/** Toolkit-neutral host font configuration. Size uses host preference units, before display scaling. */
public record FontSpec(String family, double size) {
    public FontSpec {
        family = family == null ? "" : family.strip();
        if (!Double.isFinite(size) || size <= 0) {
            throw new IllegalArgumentException("Font size must be finite and positive");
        }
    }

    /** An empty family means follow the toolkit's system UI font, including its fallback fonts. */
    public boolean systemDefault() { return family.isEmpty(); }
}
