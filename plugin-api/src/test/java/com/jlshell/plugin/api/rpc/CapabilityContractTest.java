package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityContractTest {

    @Test
    void emptyRegistryIsNoOp() {
        CapabilityRegistry empty = CapabilityRegistry.empty();
        empty.register(null);                       // no throw
        empty.unregister("x");                      // no throw
        assertThat(empty.specs()).isEmpty();
        assertThat(empty.resolve("anything")).isEmpty();
    }

    @Test
    void builderProducesCapability() {
        JsonObject schema = new JsonObject();
        Capability cap = Capability.builder("readConfig")
                .description("d")
                .inputSchema(schema)
                .requiresSession(true)
                .handler((args, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(args))
                .build();
        assertThat(cap.spec().name()).isEqualTo("readConfig");
        assertThat(cap.spec().requiresSession()).isTrue();
        assertThat(cap.handler()).isNotNull();
        // pluginId is null until host injects it
        assertThat(cap.pluginId()).isNull();
    }

    @Test
    void builderRequiresNameAndHandler() {
        assertThatThrownBy(() -> Capability.builder("  ").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Capability.builder("ok").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
