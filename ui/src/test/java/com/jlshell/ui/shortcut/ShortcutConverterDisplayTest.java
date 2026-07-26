package com.jlshell.ui.shortcut;

import com.jlshell.core.shortcut.ShortcutConverter;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortcutConverterDisplayTest {

    @Test
    void separatesShortcutPartsForCurrentPlatform() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

        assertEquals(mac ? "⌃\u202f⇧\u202fF" : "Ctrl + Shift + F",
                ShortcutConverter.toDisplayText("ctrl shift F"));
        assertEquals(mac ? "⌘\u202fC" : "Ctrl + C",
                ShortcutConverter.toDisplayText("meta C"));
    }
}
