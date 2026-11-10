package com.jlshell.ui.swing;

import java.util.function.Supplier;
import javax.swing.JComponent;

/** Native plugin page factory carried through the host's remaining form models. */
public final class SwingPageModel extends javafx.scene.layout.Region {
    final Supplier<JComponent> factory;

    private SwingPageModel(Supplier<JComponent> factory) { this.factory = factory; }

    public static SwingPageModel wrap(Supplier<JComponent> factory) {
        return factory == null ? null : new SwingPageModel(factory);
    }
}
