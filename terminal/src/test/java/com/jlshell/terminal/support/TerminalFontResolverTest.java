package com.jlshell.terminal.support;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.impl.PersistentFontProfileService;
import org.junit.jupiter.api.Test;
import java.awt.Font;
import java.util.HashMap;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class TerminalFontResolverTest {
    @Test void independentChineseFontPreservesLatinAndStyles() {
        String chinese = TerminalFontResolver.automaticChineseFamily();
        FontProfile profile = new FontProfile("Monospaced", 16, true, 1.4, chinese);
        assertEquals(new Font("Monospaced", Font.PLAIN, 16), TerminalFontResolver.resolve(profile, 'A', Font.PLAIN));
        assertEquals(new Font(chinese, Font.BOLD | Font.ITALIC, 16), TerminalFontResolver.resolve(profile, '中', Font.BOLD | Font.ITALIC));
        assertTrue(TerminalFontResolver.isChinese('，'));
        assertTrue(TerminalFontResolver.isChinese(0x20000));
        assertFalse(TerminalFontResolver.isChinese('A'));
    }
    @Test void oldSettingsRemainValidAndNewChineseSelectionSurvivesReload() {
        var values = new HashMap<String, String>();
        AppSettingsService settings = new AppSettingsService() {
            public Optional<String> get(String key) { return Optional.ofNullable(values.get(key)); }
            public void set(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        };
        values.put("terminal.font.family", "Monospaced");
        var service = new PersistentFontProfileService(settings);
        assertEquals("", service.activeProfile().chineseFamily());
        FontProfile profile = new FontProfile("Monospaced", 16, true, 1.4, "PingFang SC");
        service.updateActiveProfile(profile);
        assertEquals(profile, new PersistentFontProfileService(settings).activeProfile());
        service.updateActiveProfile(new FontProfile("Monospaced", 13, false, 1));
        assertEquals("", new PersistentFontProfileService(settings).activeProfile().chineseFamily());
    }
}
