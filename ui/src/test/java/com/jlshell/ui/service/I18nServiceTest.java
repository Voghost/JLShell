package com.jlshell.ui.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class I18nServiceTest {
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach void restoreLocale() { Locale.setDefault(originalLocale); }

    @Test
    void listenerReadsNewTranslationsWithoutStartingAnyUiToolkit() {
        var service = new I18nService(Locale.ENGLISH);
        List<String> observed = new ArrayList<>();
        var subscription = service.onLocaleChanged(locale -> {
            assertEquals(locale, service.getLocale());
            observed.add(service.get("settings.cancel"));
        });
        service.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals(List.of("取消"), observed);
        service.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals(1, observed.size());
        subscription.close();
        subscription.close();
        service.setLocale(Locale.ENGLISH);
        assertEquals("Cancel", service.get("settings.cancel"));
        assertEquals(1, observed.size());
    }

    @Test
    void failedListenerDoesNotStopRemainingSubscribers() {
        var service = new I18nService(Locale.ENGLISH);
        service.onLocaleChanged(locale -> { throw new IllegalStateException("fixture"); });
        List<Locale> observed = new ArrayList<>();
        service.onLocaleChanged(observed::add);
        service.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals(List.of(Locale.SIMPLIFIED_CHINESE), observed);
    }

    @Test
    void englishBundleDoesNotFallBackToChineseSystemLocale() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        var service = new I18nService(Locale.ENGLISH);
        assertEquals("Cancel", service.get("settings.cancel"));
        assertEquals("missing.fixture", service.get("missing.fixture"));
        assertEquals("fallback", service.getOrDefault("missing.fixture", "fallback"));
    }
}
