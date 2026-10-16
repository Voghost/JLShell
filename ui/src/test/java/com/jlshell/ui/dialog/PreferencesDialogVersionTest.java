package com.jlshell.ui.dialog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PreferencesDialogVersionTest {
    @Test
    void doesNotUseSwingLookAndFeelVersionAsPluginHostVersion() {
        assertNotEquals("3.5.4", PreferencesDialog.getVersion());
    }
}
