package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.plugin.api.PluginScope;
import org.junit.jupiter.api.Test;

class PluginEnablementServiceTest {

    @Test
    void persistsDisabledPluginIdsSeparatelyByScope() {
        MapSettings settings = new MapSettings();
        PluginEnablementService service = new PluginEnablementService(settings);

        service.setEnabled("com.example.session", PluginScope.SESSION, false);
        service.setEnabled("com.example.program", PluginScope.PROGRAM, false);

        PluginEnablementService restored = new PluginEnablementService(settings);
        assertThat(restored.isEnabled("com.example.session", PluginScope.SESSION)).isFalse();
        assertThat(restored.isEnabled("com.example.session", PluginScope.PROGRAM)).isTrue();
        assertThat(restored.isEnabled("com.example.program", PluginScope.PROGRAM)).isFalse();

        restored.setEnabled("com.example.session", PluginScope.SESSION, true);
        assertThat(new PluginEnablementService(settings)
                .isEnabled("com.example.session", PluginScope.SESSION)).isTrue();
    }

    @Test
    void malformedSettingFallsBackToEnabled() {
        MapSettings settings = new MapSettings();
        settings.set("plugins.disabled.session", "not-json");

        PluginEnablementService service = new PluginEnablementService(settings);

        assertThat(service.isEnabled("com.example.plugin", PluginScope.SESSION)).isTrue();
    }

    private static final class MapSettings implements AppSettingsService {
        private final Map<String, String> values = new HashMap<>();

        @Override public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override public void set(String key, String value) {
            values.put(key, value);
        }

        @Override public void remove(String key) {
            values.remove(key);
        }
    }
}
