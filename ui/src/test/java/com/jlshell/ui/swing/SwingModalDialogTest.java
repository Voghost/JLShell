package com.jlshell.ui.swing;

import com.jlshell.terminal.support.SwingExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingModalDialogTest {
    @Test void restoresOwnerBeforeHidingAndPreservesNestedOwnerState() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            JFrame frame = new JFrame("JLShell modal fixture");
            frame.setSize(600, 400); frame.setVisible(true);
            try {
                for (int action = 0; action < 6; action++) {
                    var parent = new SwingModalDialog(frame, "About or folder");
                    parent.setSize(300, 200); parent.setLocationRelativeTo(frame); parent.setVisible(true);
                    assertFalse(frame.isEnabled());
                    assertEquals(Boolean.TRUE, frame.getRootPane().getClientProperty("jlshell.settingsModal"));
                    var nested = new SwingModalDialog(parent, "Nested notice");
                    nested.setSize(200, 120); nested.setVisible(true);
                    assertFalse(parent.isEnabled());
                    nested.dispose();
                    assertTrue(parent.isEnabled());
                    assertFalse(frame.isEnabled(), "Nested close must leave the main window blocked");
                    if (action % 2 == 0) parent.setVisible(false); else parent.dispose();
                    assertTrue(frame.isEnabled());
                    assertNull(frame.getRootPane().getClientProperty("jlshell.settingsModal"));
                    assertEquals(Frame.NORMAL, frame.getExtendedState(), "Dialog close must not iconify the owner");
                    assertTrue(frame.isShowing());
                    parent.dispose();
                }
                frame.setEnabled(false);
                var dialog = new SwingModalDialog(frame, "Already blocked owner");
                dialog.setSize(200, 120); dialog.setVisible(true); dialog.dispose();
                assertFalse(frame.isEnabled(), "Restore the prior state rather than unconditionally enabling");
            } finally { frame.setEnabled(true); frame.dispose(); }
        }).get(15, TimeUnit.SECONDS);
    }
}
