package com.jlshell.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginContextSwingCompatibilityTest {
    @Test
    void contextDoesNotExecuteUnsupportedSwingFactory() {
        ExistingContext context = new ExistingContext();
        AtomicBoolean factoryInvoked = new AtomicBoolean();

        context.openTab("Existing view", null);
        assertThat(context.title).isEqualTo("Existing view");
        assertThat(context.supportsSwingViews()).isFalse();
        assertThatThrownBy(() -> context.openSwingTab("New view", () -> {
            factoryInvoked.set(true);
            return null;
        })).isInstanceOf(UnsupportedOperationException.class);
        assertThat(factoryInvoked).isFalse();
    }

    /** A context can expose metadata while rejecting UI creation. */
    private static final class ExistingContext implements PluginContext {
        private String title;
        @Override public String themeName() { return "dark"; }
        @Override public com.jlshell.plugin.api.ui.ObservableValue<String> themeNameProperty() { return null; }
        @Override public Locale locale() { return Locale.ROOT; }
        @Override public com.jlshell.plugin.api.ui.ObservableValue<Locale> localeProperty() { return null; }
        @Override public Optional<SshSessionContext> sshSession() { return Optional.empty(); }
        @Override public void openTab(String title, java.util.function.Supplier<javax.swing.JComponent> content) { this.title = title; }
        @Override public void closeTab() {}
        @Override public void updateTabTitle(String title) { this.title = title; }
        @Override public String resolveI18n(String key, String fallback) { return fallback; }
        @Override public void showNotification(String message, NotificationLevel level) {}
    }
}
