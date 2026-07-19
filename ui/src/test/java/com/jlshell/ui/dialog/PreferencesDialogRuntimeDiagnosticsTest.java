package com.jlshell.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreferencesDialogRuntimeDiagnosticsTest {

    @Test
    void reportContainsUsefulRuntimeDetailsWithoutUserPaths() {
        String report = PreferencesDialog.buildRuntimeDiagnostics();

        assertTrue(report.contains("JLShell Environment Report"));
        assertTrue(report.contains("- JLShell:"));
        assertTrue(report.contains("- JavaFX:"));
        assertTrue(report.contains("- OS:"));
        assertTrue(report.contains("- Architecture:"));
        assertTrue(report.contains("- Locale:"));

        String userHome = System.getProperty("user.home", "");
        if (userHome.length() > 1) {
            assertFalse(report.contains(userHome));
        }
    }
}
