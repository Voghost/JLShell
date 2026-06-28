package com.jlshell.program.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonPrimitive;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.loader.CapabilityBusImpl;
import com.jlshell.plugin.loader.PluginManager;
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

        descriptor.instance().activate(descriptor.context());

        assertThat(bus.listRegisteredCapabilities(null)).hasSize(1);
        assertThat(bus.invoke(new RpcRequest(null, "com.test.program", "ping", new JsonPrimitive("ok"), "1"))
                .get().result().getAsString()).isEqualTo("ok");

        descriptor.instance().deactivate();
        sessionManager.globalRegistry().clearForPlugin(descriptor.id());
        assertThat(bus.listRegisteredCapabilities(null)).isEmpty();
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

    private static class TestProgramPlugin implements JlShellProgramPlugin {
        @Override public String id() { return "com.test.program"; }
        @Override public String displayName() { return "Program"; }
        @Override public String version() { return "1.0.0"; }
        @Override public String minHostVersionInclusive() { return "1.0.0"; }
        @Override public String maxHostVersionInclusive() { return "1.0.0"; }
        @Override public String description() { return ""; }
        @Override public void activate(ProgramPluginContext context) {
            context.capabilityRegistry().register(Capability.builder("ping")
                    .handler((args, ctx) -> CompletableFuture.completedFuture(args))
                    .build());
        }
        @Override public void deactivate() {}
    }
}
