package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.rpc.Capability;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
        // Fix 1 后 registryFor 对未知 sessionId 返回共享空哨兵、不再自动建桶。
        // 这里需先确保 session 桶存在，再取桶的 registry 构造 ctx，使 ctx 的 registry
        // 与桶的 registry 是同一对象（activateInstance 注册进桶，registryFor 读桶）。
        // adoptContext 的 contexts 是 ConcurrentHashMap 不接受 null，故用占位 ctx 先建桶。
        DefaultPluginContext placeholder = new DefaultPluginContext(pluginId, Optional.empty(), new DefaultPluginContext.Callbacks() {
            @Override public void openTab(String t, javafx.scene.Node n) {}
            @Override public void closeTab() {}
            @Override public void updateTabTitle(String t) {}
            @Override public String resolveI18n(String k, String f) { return f; }
        });
        mgr.adoptContext(sessionId, pluginId, placeholder);
        DefaultPluginContext ctx = new DefaultPluginContext(pluginId, sessionId,
                mgr.registryFor(sessionId),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
        mgr.adoptContext(sessionId, pluginId, ctx);
        return ctx;
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

    @Test
    void deactivatingPluginUnbindsContextProperties() {
        PluginManager mgr = new PluginManager();
        CapPlugin plugin = new CapPlugin("com.test.cap");
        DefaultPluginContext ctx = ctxFor(mgr, "com.test.cap", "sess-A");
        ctx.writableThemeNameProperty().bind(mgr.themeNameProperty());
        ctx.writableLocaleProperty().bind(mgr.localeProperty());
        mgr.activateInstance(plugin, ctx);

        mgr.deactivatePlugin("sess-A", "com.test.cap");

        assertThat(ctx.writableThemeNameProperty().isBound()).isFalse();
        assertThat(ctx.writableLocaleProperty().isBound()).isFalse();
        ctx.writableThemeNameProperty().set("light");
        ctx.writableLocaleProperty().set(Locale.CHINA);
        assertThat(ctx.themeName()).isEqualTo("light");
        assertThat(ctx.locale()).isEqualTo(Locale.CHINA);
    }

    @Test
    void reloadingPluginsPublishesCatalogRevision() {
        PluginManager mgr = new PluginManager("target/non-existent-plugin-directory");
        long before = mgr.catalogRevisionProperty().get();

        mgr.reloadPlugins();

        assertThat(mgr.catalogRevisionProperty().get()).isGreaterThan(before);
    }

    @Test
    void deactivatingPluginClosesItsOpenTab() {
        PluginManager mgr = new PluginManager();
        CapPlugin plugin = new CapPlugin("com.test.close-tab");
        AtomicInteger closeCalls = new AtomicInteger();
        String sessionId = "sess-A";
        mgr.registryForSession(sessionId);
        DefaultPluginContext ctx = new DefaultPluginContext(plugin.id(), sessionId,
                mgr.registryFor(sessionId), Optional.empty(), new DefaultPluginContext.Callbacks() {
            @Override public void openTab(String t, javafx.scene.Node n) {}
            @Override public void closeTab() { closeCalls.incrementAndGet(); }
            @Override public void updateTabTitle(String t) {}
            @Override public String resolveI18n(String k, String f) { return f; }
        });
        mgr.adoptContext(sessionId, plugin.id(), ctx);
        mgr.activateInstance(plugin, ctx);

        mgr.deactivatePlugin(sessionId, plugin.id());

        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void disablingPluginStopsActiveInstancesAndPublishesCatalogChange() {
        PluginEnablementService enablement = new PluginEnablementService();
        PluginManager mgr = new PluginManager("target/non-existent-plugin-directory", "1.0.0", enablement);
        CapPlugin plugin = new CapPlugin("com.test.disabled");
        DefaultPluginContext ctx = ctxFor(mgr, plugin.id(), "sess-A");
        mgr.activateInstance(plugin, ctx);
        long before = mgr.catalogRevisionProperty().get();

        mgr.setPluginEnabled(plugin.id(), false);

        assertThat(mgr.isPluginEnabled(plugin.id())).isFalse();
        assertThat(mgr.isPluginActive("sess-A", plugin.id())).isFalse();
        assertThat(mgr.catalogRevisionProperty().get()).isGreaterThan(before);
    }
}
