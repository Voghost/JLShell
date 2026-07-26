package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.api.security.PluginAccessDecision;
import com.jlshell.plugin.api.security.PluginAccessRequest;
import com.jlshell.plugin.api.security.PluginOperation;
import org.junit.jupiter.api.Test;

class PluginAccessControllerTest {

    private static final PluginAccessRequest REQUEST = new PluginAccessRequest(
            PluginOperation.ACTIVATE, PluginScope.SESSION, "session-1", "com.example.plugin", null);

    @Test
    void denyWinsAndUnregisterRestoresDefaultAllow() {
        PluginAccessController controller = new PluginAccessController();
        controller.registerTrusted("allow", request -> PluginAccessDecision.allow());
        controller.registerTrusted("deny", request -> PluginAccessDecision.deny("plan required"));

        assertThat(controller.evaluate(REQUEST).effect()).isEqualTo(PluginAccessDecision.Effect.DENY);
        assertThat(controller.evaluate(REQUEST).reason()).isEqualTo("plan required");

        controller.unregister("deny");
        assertThat(controller.evaluate(REQUEST).effect()).isEqualTo(PluginAccessDecision.Effect.ALLOW);
    }

    @Test
    void providerFailureFailsClosed() {
        PluginAccessController controller = new PluginAccessController();
        controller.registerTrusted("broken", request -> { throw new IllegalStateException("boom"); });

        assertThat(controller.evaluate(REQUEST).effect()).isEqualTo(PluginAccessDecision.Effect.DENY);
        assertThat(controller.evaluate(REQUEST).reason()).contains("provider failed");
    }
}
