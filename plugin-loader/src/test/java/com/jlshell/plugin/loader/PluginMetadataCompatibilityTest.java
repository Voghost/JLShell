package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginCompatibilityStatus;
import com.jlshell.plugin.api.PluginContext;
import org.junit.jupiter.api.Test;

class PluginMetadataCompatibilityTest {

    @Test
    void oldSessionPluginDefaultsToSystemAuthorAndUndeclaredCompatibility() {
        PluginManager manager = new PluginManager("/not/exist", "1.0.0");
        JlShellPlugin plugin = new SimplePlugin("com.test.old", "", "");

        PluginDescriptor descriptor = descriptor(manager, plugin);

        assertThat(descriptor.author()).isEqualTo("system");
        assertThat(descriptor.compatibilityStatus()).isEqualTo(PluginCompatibilityStatus.UNDECLARED);
    }

    @Test
    void incompatibleSessionPluginCanStillActivateInV1() {
        PluginManager manager = new PluginManager("/not/exist", "1.0.0");
        SimplePlugin plugin = new SimplePlugin("com.test.future", "2.0.0", "");

        manager.activateInstance(plugin, new DefaultPluginContext(plugin.id(), Optional.empty(), callbacks()));

        assertThat(plugin.activated).isTrue();
        assertThat(descriptor(manager, plugin).compatibilityStatus()).isEqualTo(PluginCompatibilityStatus.INCOMPATIBLE);
    }

    private static PluginDescriptor descriptor(PluginManager manager, JlShellPlugin plugin) {
        try {
            java.lang.reflect.Method method = PluginManager.class.getDeclaredMethod("toDescriptor", JlShellPlugin.class);
            method.setAccessible(true);
            return (PluginDescriptor) method.invoke(manager, plugin);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static DefaultPluginContext.Callbacks callbacks() {
        return new DefaultPluginContext.Callbacks() {
            @Override public void openTab(String title, javafx.scene.Node content) {}
            @Override public void closeTab() {}
            @Override public void updateTabTitle(String title) {}
            @Override public String resolveI18n(String key, String fallback) { return fallback; }
        };
    }

    private static class SimplePlugin implements JlShellPlugin {
        private final String id;
        private final String min;
        private final String max;
        private boolean activated;

        private SimplePlugin(String id, String min, String max) {
            this.id = id;
            this.min = min;
            this.max = max;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String version() { return "1.0.0"; }
        @Override public String minHostVersionInclusive() { return min; }
        @Override public String maxHostVersionInclusive() { return max; }
        @Override public String description() { return ""; }
        @Override public boolean requiresSshSession() { return false; }
        @Override public void activate(PluginContext context) { activated = true; }
        @Override public void deactivate() {}
    }
}
