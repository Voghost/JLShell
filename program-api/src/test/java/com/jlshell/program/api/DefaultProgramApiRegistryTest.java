package com.jlshell.program.api;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonNull;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultProgramApiRegistryTest {

    @Test
    void rejectsDuplicateMethodRegistration() {
        DefaultProgramApiRegistry registry = new DefaultProgramApiRegistry();
        registry.register("session.list", ignored -> CompletableFuture.completedFuture(JsonNull.INSTANCE));

        assertThatThrownBy(() -> registry.register("session.list",
                ignored -> CompletableFuture.completedFuture(JsonNull.INSTANCE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session.list");
    }
}
