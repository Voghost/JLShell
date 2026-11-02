package com.jlshell.terminal.support;

import javax.swing.event.ChangeEvent;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Lets the thumb follow the pointer while the terminal catches up through short interpolation. */
class TerminalScrollBarUI extends BasicScrollBarUI {
    @Override protected ModelListener createModelListener() {
        return new ModelListener() {
            @Override public void stateChanged(ChangeEvent event) {
                // As in IDEA's JBScrollBar.Model, presentation updates must not pull the
                // thumb away from the coordinates already chosen by the drag listener.
                if (isDragging && scrollbar.getValueIsAdjusting()) return;
                super.stateChanged(event);
            }
        };
    }
}
