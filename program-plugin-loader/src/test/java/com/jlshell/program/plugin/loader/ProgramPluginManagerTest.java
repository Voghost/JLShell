package com.jlshell.program.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.List;

import com.google.gson.JsonPrimitive;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.security.PluginAccessDecision;
import com.jlshell.plugin.api.security.PluginAccessPolicyProvider;
import com.jlshell.plugin.loader.CapabilityBusImpl;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.loader.PluginEnablementService;
import org.junit.jupiter.api.Test;

class ProgramPluginManagerTest {

    @Test
    void programPluginRegistersGlobalCapability() throws Exception {
        PluginManager sessionManager = new PluginManager("/not/exist", "1.0.0");
        CapabilityBus bus = new CapabilityBusImpl(sessionManager);
        TestProgramPlugin plugin = new TestProgramPlugin();
        ProgramPluginManager manager = new ProgramPluginManager(
                "/not/exist",
                "1.0.0",
                sessionManager,
                sessionManager.globalRegistry(),
                bus,
                id -> null,
                (key, fallback) -> fallback
        );
        ProgramPluginDescriptor descriptor = descriptor(manager, plugin);

        sessionManager.adoptContext(null, descriptor.id(), (PluginContext) descriptor.context());
        descriptor.instance().activate(descriptor.context());

        assertThat(bus.listRegisteredCapabilities(null)).hasSize(1);
        assertThat(bus.invoke(new RpcRequest(null, "com.test.program", "ping", new JsonPrimitive("ok"), "1"))
                .get().result().getAsString()).isEqualTo("ok");

        descriptor.instance().deactivate();
        sessionManager.globalRegistry().clearForPlugin(descriptor.id());
        assertThat(bus.listRegisteredCapabilities(null)).isEmpty();
    }

    @Test
    void disablingProgramPluginDeactivatesItAndKeepsItManageable() throws Exception {
        PluginManager sessionManager = new PluginManager("/not/exist", "1.0.0");
        CapabilityBus bus = new CapabilityBusImpl(sessionManager);
        PluginEnablementService enablement = new PluginEnablementService();
        ProgramPluginManager manager = new ProgramPluginManager(
                "/not/exist", "1.0.0", sessionManager, sessionManager.globalRegistry(), bus,
                id -> null, (key, fallback) -> fallback, null, enablement);
        TestProgramPlugin plugin = new TestProgramPlugin();
        addDescriptorAndMarkLoaded(manager, descriptor(manager, plugin));
        manager.activateAll();

        manager.setPluginEnabled(plugin.id(), false);

        assertThat(plugin.deactivateCount).isEqualTo(1);
        assertThat(manager.getAvailablePlugins()).isEmpty();
        assertThat(manager.getInstalledPlugins()).hasSize(1);

        manager.setPluginEnabled(plugin.id(), true);
        assertThat(plugin.activateCount).isEqualTo(2);
        assertThat(manager.getAvailablePlugins()).hasSize(1);
    }

    private static ProgramPluginDescriptor descriptor(ProgramPluginManager manager, JlShellProgramPlugin plugin) {
        try {
            java.lang.reflect.Method method = ProgramPluginManager.class.getDeclaredMethod("toDescriptor", JlShellProgramPlugin.class);
            method.setAccessible(true);
            return (ProgramPluginDescriptor) method.invoke(manager, plugin);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addDescriptorAndMarkLoaded(ProgramPluginManager manager,
                                                   ProgramPluginDescriptor descriptor) {
        try {
            java.lang.reflect.Field plugins = ProgramPluginManager.class.getDeclaredField("plugins");
            plugins.setAccessible(true);
            ((List<ProgramPluginDescriptor>) plugins.get(manager)).add(descriptor);
            java.lang.reflect.Field loaded = ProgramPluginManager.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            loaded.setBoolean(manager, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static class TestProgramPlugin implements JlShellProgramPlugin {
        int activateCount;
        int deactivateCount;
        @Override public String id() { return "com.test.program"; }
        @Override public String displayName() { return "Program"; }
        @Override public String version() { return "1.0.0"; }
        @Override public String minHostVersionInclusive() { return "1.0.0"; }
        @Override public String maxHostVersionInclusive() { return "1.0.0"; }
        @Override public String description() { return ""; }
        @Override public void activate(ProgramPluginContext context) {
            activateCount++;
            context.capabilityRegistry().register(Capability.builder("ping")
                    .handler((args, ctx) -> CompletableFuture.completedFuture(args))
                    .build());
        }
        @Override public void deactivate() { deactivateCount++; }
    }

    private static final class PolicyPlugin extends TestProgramPlugin {
        private final List<String> order;
        private PolicyPlugin(List<String> order) { this.order = order; }
        @Override public String id() { return "com.test.policy"; }
        @Override public void activate(ProgramPluginContext context) { order.add("policy"); }
        @Override public PluginAccessPolicyProvider accessPolicyProvider() {
            return request -> "com.test.paid".equals(request.pluginId())
                    ? PluginAccessDecision.deny("subscription required")
                    : PluginAccessDecision.abstain();
        }
    }

    private static final class TrackingPlugin extends TestProgramPlugin {
        private final List<String> order;
        private boolean activated;
        private TrackingPlugin(List<String> order) { this.order = order; }
        @Override public String id() { return "com.test.paid"; }
        @Override public void activate(ProgramPluginContext context) {
            activated = true;
            order.add("paid");
        }
    }
}
