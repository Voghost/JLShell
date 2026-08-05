package com.jlshell.program.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution;
import org.junit.jupiter.api.Test;

class ProgramConnectionIntegrationRegistryTest {

    private static final ConnectionRouteRequest REQUEST = new ConnectionRouteRequest(
            "connection", "project", "test", "192.168.31.20", 22, "tester");

    @Test
    void returnsNoRouteWhenNoPluginClaimsTheConnection() {
        ProgramConnectionIntegrationRegistry registry = new ProgramConnectionIntegrationRegistry();

        assertThat(registry.route(REQUEST).join()).isNull();
    }

    @Test
    void registrationIsScopedAndCannotBeRemovedByAnOlderHandle() {
        ProgramConnectionIntegrationRegistry registry = new ProgramConnectionIntegrationRegistry();
        var first = registry.scoped("plugin-a").register(contribution(true, 2201));
        registry.scoped("plugin-a").register(contribution(true, 2202));

        first.close();
        assertThat(registry.route(REQUEST).join().port()).isEqualTo(2202);

        registry.clearForPlugin("plugin-a");
        assertThat(registry.route(REQUEST).join()).isNull();
    }

    @Test
    void rejectsAmbiguousOrBrokenContributionsAsConnectionFailures() {
        ProgramConnectionIntegrationRegistry registry = new ProgramConnectionIntegrationRegistry();
        registry.scoped("plugin-a").register(contribution(true, 2201));
        registry.scoped("plugin-b").register(contribution(true, 2202));

        assertThatThrownBy(() -> registry.route(REQUEST).join())
                .hasMessageContaining("multiple Program plugins");

        registry.clearForPlugin("plugin-b");
        registry.scoped("plugin-a").register(new ProgramConnectionRouteContribution() {
            @Override public boolean supports(ConnectionRouteRequest request) {
                throw new IllegalStateException("broken routing plugin");
            }
            @Override public CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
                return CompletableFuture.completedFuture(ConnectionRoute.loopback("127.0.0.1", 2201, null));
            }
        });
        assertThatThrownBy(() -> registry.route(REQUEST).join())
                .hasMessageContaining("broken routing plugin");
    }

    private static ProgramConnectionRouteContribution contribution(boolean supports, int port) {
        return new ProgramConnectionRouteContribution() {
            @Override public boolean supports(ConnectionRouteRequest request) { return supports; }
            @Override public CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
                return CompletableFuture.completedFuture(ConnectionRoute.loopback("127.0.0.1", port, null));
            }
        };
    }
}
