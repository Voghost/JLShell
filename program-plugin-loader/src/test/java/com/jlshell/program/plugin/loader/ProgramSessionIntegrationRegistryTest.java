package com.jlshell.program.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.capability.InteractiveCommandExecutor;
import com.jlshell.plugin.api.capability.LogViewer;
import com.jlshell.plugin.api.capability.ServerStatusProvider;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.loader.CapabilityBusImpl;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginManager;
import org.junit.jupiter.api.Test;

class ProgramSessionIntegrationRegistryTest {

    @Test
    void registrationOwnsEverySessionActivationAndClosesThemTogether() {
        PluginManager pluginManager = new PluginManager("/not/exist", "1.0.0");
        ProgramSessionIntegrationRegistry registry = new ProgramSessionIntegrationRegistry(pluginManager);
        AtomicInteger activated = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger tabsClosed = new AtomicInteger();
        var registration = registry.scoped("com.test.program").register(contribution(activated, closed));

        DefaultPluginContext first = context(pluginManager, "session-a", tabsClosed);
        DefaultPluginContext second = context(pluginManager, "session-b", tabsClosed);
        assertThat(registry.activate("com.test.program", "session-a", first)).isTrue();
        assertThat(registry.activate("com.test.program", "session-b", second)).isTrue();
        assertThat(activated).hasValue(2);

        registration.close();
        registration.close();

        assertThat(closed).hasValue(2);
        assertThat(tabsClosed).hasValue(2);
        assertThat(registry.contributions(new TestSshSession("session-a"))).isEmpty();
        assertThat(registry.isActive("session-a", "com.test.program")).isFalse();
        assertThat(registry.isActive("session-b", "com.test.program")).isFalse();
    }

    @Test
    void sessionCloseOnlyReleasesThatSessionsController() {
        PluginManager pluginManager = new PluginManager("/not/exist", "1.0.0");
        ProgramSessionIntegrationRegistry registry = new ProgramSessionIntegrationRegistry(pluginManager);
        AtomicInteger activated = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        registry.scoped("com.test.program").register(contribution(activated, closed));
        registry.activate("com.test.program", "session-a", context(pluginManager, "session-a", new AtomicInteger()));
        registry.activate("com.test.program", "session-b", context(pluginManager, "session-b", new AtomicInteger()));

        registry.deactivateSession("session-a");

        assertThat(closed).hasValue(1);
        assertThat(registry.isActive("session-a", "com.test.program")).isFalse();
        assertThat(registry.isActive("session-b", "com.test.program")).isTrue();
    }

    private static ProgramSessionContribution contribution(
            AtomicInteger activated, AtomicInteger closed) {
        return new ProgramSessionContribution() {
            @Override public String displayName() { return "Test"; }
            @Override public String description() { return "Test contribution"; }
            @Override public com.jlshell.plugin.api.session.ProgramSessionController activate(PluginContext context) {
                activated.incrementAndGet();
                context.openTab("Test", new javafx.scene.layout.Pane());
                return closed::incrementAndGet;
            }
        };
    }

    private static DefaultPluginContext context(
            PluginManager pluginManager, String sessionId, AtomicInteger tabsClosed) {
        return new DefaultPluginContext(
                "com.test.program", sessionId, pluginManager.registryForSession(sessionId),
                new CapabilityBusImpl(pluginManager), null,
                Optional.of(new TestSshSession(sessionId)), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String title, javafx.scene.Node content) { }
                    @Override public void closeTab() { tabsClosed.incrementAndGet(); }
                    @Override public void updateTabTitle(String title) { }
                    @Override public String resolveI18n(String key, String fallback) { return fallback; }
                });
    }

    private record TestSshSession(String sessionId) implements SshSessionContext {
        @Override public String displayName() { return "Test"; }
        @Override public String host() { return "127.0.0.1"; }
        @Override public int port() { return 22; }
        @Override public String username() { return "tester"; }
        @Override public CommandExecutor commandExecutor() { return null; }
        @Override public InteractiveCommandExecutor interactiveCommandExecutor() { return null; }
        @Override public FileExplorer fileExplorer() { return null; }
        @Override public LogViewer logViewer() { return null; }
        @Override public ServerStatusProvider serverStatus() { return null; }
    }
}
