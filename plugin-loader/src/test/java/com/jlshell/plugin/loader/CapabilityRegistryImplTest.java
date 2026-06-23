package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.rpc.Capability;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryImplTest {

    private Capability echoCap() {
        return Capability.builder("echo").requiresSession(false)
                .handler((args, ctx) -> CompletableFuture.completedFuture(args))
                .build();
    }

    @Test
    void registerAndResolveByPluginAndName() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        assertThat(reg.resolve("com.a", "echo")).isPresent();
        assertThat(reg.resolve("com.b", "echo")).as("different plugin, not found").isEmpty();
    }

    @Test
    void specsListAllWithPluginId() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        reg.register("com.a", Capability.builder("ping").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        reg.register("com.b", Capability.builder("pong").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        assertThat(reg.specs()).hasSize(3);
    }

    @Test
    void clearForPluginRemovesOnlyThatPlugin() {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        reg.register("com.b", Capability.builder("pong").handler((a,c)->CompletableFuture.completedFuture(a)).build());
        reg.clearForPlugin("com.a");
        assertThat(reg.resolve("com.a", "echo")).isEmpty();
        assertThat(reg.resolve("com.b", "pong")).isPresent();
    }

    @Test
    void invokeHandlerReturnsJson() throws Exception {
        CapabilityRegistryImpl reg = new CapabilityRegistryImpl();
        reg.register("com.a", echoCap());
        Capability cap = reg.resolve("com.a", "echo").orElseThrow();
        JsonElement out = cap.handler().invoke(new JsonPrimitive("hi"), null).get();
        assertThat(out.toString()).isEqualTo("\"hi\"");
    }
}
