package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.rpc.Capability;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class PluginManagerPerSessionTest {

    // 一个会向 registry 注册能力并记录自己 ctx 的最小插件
    static class CapPlugin implements JlShellPlugin {
        final String id;
        PluginContext ctx;
        CapPlugin(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String version() { return "1"; }
        @Override public String description() { return ""; }
        @Override public boolean requiresSshSession() { return false; }
        @Override public void activate(PluginContext ctx) {
            this.ctx = ctx;
            ctx.capabilityRegistry().register(
                Capability.builder("ping").handler((a, c) -> CompletableFuture.completedFuture(a)).build());
        }
        @Override public void deactivate() {}
    }

    private DefaultPluginContext ctxFor(PluginManager mgr, String pluginId, String sessionId) {
        return new DefaultPluginContext(pluginId, sessionId,
                mgr.registryFor(sessionId),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
    }

    @Test
    void samePluginInTwoSessionsIsIsolated() {
        PluginManager mgr = new PluginManager(); // 不加载外部 jar
        // 直接构造插件实例并经 per-session 路径激活
        CapPlugin a1 = new CapPlugin("com.test.cap");
        CapPlugin a2 = new CapPlugin("com.test.cap");
        DefaultPluginContext c1 = ctxFor(mgr, "com.test.cap", "sess-A");
        DefaultPluginContext c2 = ctxFor(mgr, "com.test.cap", "sess-B");
        mgr.activateInstance(a1, c1);
        mgr.activateInstance(a2, c2);
        assertThat(a1.ctx).isNotSameAs(a2.ctx);
        // 两个 session 的 registry 互不影响
        assertThat(mgr.registryFor("sess-A").resolve("com.test.cap", "ping")).isPresent();
        assertThat(mgr.registryFor("sess-B").resolve("com.test.cap", "ping")).isPresent();
        // 停 A 不影响 B
        mgr.deactivatePlugin("sess-A", "com.test.cap");
        assertThat(mgr.registryFor("sess-A").resolve("com.test.cap", "ping")).isEmpty();
        assertThat(mgr.registryFor("sess-B").resolve("com.test.cap", "ping")).isPresent();
    }
}
