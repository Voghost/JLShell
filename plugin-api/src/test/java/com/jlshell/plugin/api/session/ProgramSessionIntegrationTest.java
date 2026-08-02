package com.jlshell.plugin.api.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgramSessionIntegrationTest {

    @Test
    void unavailableIntegrationIsBackwardCompatibleNoOp() {
        ProgramSessionIntegration integration = ProgramSessionIntegration.unavailable();

        assertThat(integration.available()).isFalse();
        integration.register(null).close();
    }
}
