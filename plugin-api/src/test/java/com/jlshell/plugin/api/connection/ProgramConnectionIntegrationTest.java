package com.jlshell.plugin.api.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProgramConnectionIntegrationTest {

    @Test
    void unavailableIntegrationIsBackwardCompatibleNoOp() {
        ProgramConnectionIntegration integration = ProgramConnectionIntegration.unavailable();

        assertThat(integration.available()).isFalse();
        integration.register(null).close();
    }

    @Test
    void routeAcceptsOnlyLoopbackTargets() {
        assertThat(ConnectionRoute.loopback("127.0.0.1", 2222, null).host()).isEqualTo("127.0.0.1");
        assertThat(ConnectionRoute.loopback("::1", 2222, null).host()).isEqualTo("::1");

        assertThatThrownBy(() -> ConnectionRoute.loopback("192.168.31.20", 22, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
