package com.jlshell.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.program.plugin.loader.ProgramConnectionIntegrationRegistry;

class ProgramConnectionRouteServiceTest {

    @Test
    void routesFormTestConnectionThroughProjectContribution() throws Exception {
        ProgramConnectionIntegrationRegistry registry = new ProgramConnectionIntegrationRegistry();
        AtomicReference<ConnectionRouteRequest> captured = new AtomicReference<>();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        registry.scoped("test.link").register(new com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution() {
            @Override
            public boolean supports(ConnectionRouteRequest request) {
                captured.set(request);
                return "project-link".equals(request.projectId());
            }

            @Override
            public java.util.concurrent.CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        ConnectionRoute.loopback("127.0.0.1", 41234, () -> leaseClosed.set(true)));
            }
        });

        ProgramConnectionRouteService service = new ProgramConnectionRouteService(registry);
        ConnectionRequest original = request("192.168.31.212", 22);

        ProgramConnectionRouteService.RoutedConnection routed = service
                .route("connection-1", "project-link", "archlinux", original)
                .join();

        assertEquals("connection-1", captured.get().connectionId());
        assertEquals("project-link", captured.get().projectId());
        assertEquals("192.168.31.212", captured.get().host());
        assertEquals(22, captured.get().port());
        assertEquals("127.0.0.1", routed.request().target().host());
        assertEquals(41234, routed.request().target().port());
        assertSame(original.credential(), routed.request().credential());

        ProgramConnectionRouteService.closeQuietly(routed.lease());
        assertTrue(leaseClosed.get());
    }

    private static ConnectionRequest request(String host, int port) {
        return new ConnectionRequest("archlinux",
                new ConnectionTarget(host, port, "voghost", Duration.ofSeconds(10), null),
                AuthenticationMethod.PASSWORD, CredentialPayload.forPassword("secret".toCharArray()),
                HostKeyVerificationMode.STRICT);
    }
}
