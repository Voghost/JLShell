package com.jlshell.ui.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.program.plugin.loader.ProgramConnectionIntegrationRegistry;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.ui.model.ConnectionProfile;

/** 将 Program 插件的连接前本地回环路由安全应用到一次 SSH 建连。 */
public final class ProgramConnectionRouteService {

    private static final AutoCloseable NOOP_LEASE = () -> { };
    private final ProgramConnectionIntegrationRegistry connectionRoutes;

    public ProgramConnectionRouteService(ProgramPluginManager programPlugins) {
        this(programPlugins == null ? null : programPlugins.connectionIntegrationRegistry());
    }

    ProgramConnectionRouteService(ProgramConnectionIntegrationRegistry connectionRoutes) {
        this.connectionRoutes = connectionRoutes;
    }

    public CompletableFuture<RoutedConnection> route(ConnectionProfile profile, ConnectionRequest request) {
        Objects.requireNonNull(profile, "profile");
        return route(profile.id(), profile.projectId(), profile.displayName(), request);
    }

    public CompletableFuture<RoutedConnection> route(String connectionId, String projectId,
                                                      String displayName, ConnectionRequest request) {
        Objects.requireNonNull(request, "request");
        if (connectionRoutes == null) {
            return CompletableFuture.completedFuture(new RoutedConnection(request, NOOP_LEASE));
        }
        ConnectionRouteRequest routeRequest = new ConnectionRouteRequest(
                connectionId == null ? "" : connectionId, projectId, displayName == null ? "" : displayName,
                request.target().host(), request.target().port(), request.target().username());
        return connectionRoutes.route(routeRequest)
                .thenApply(route -> route == null
                        ? new RoutedConnection(request, NOOP_LEASE)
                        : apply(request, route));
    }

    private static RoutedConnection apply(ConnectionRequest request, ConnectionRoute route) {
        ConnectionTarget original = request.target();
        ConnectionTarget local = new ConnectionTarget(route.host(), route.port(), original.username(),
                original.connectTimeout(), original.readTimeout());
        return new RoutedConnection(new ConnectionRequest(request.displayName(), local,
                request.authenticationMethod(), request.credential(), request.hostKeyVerificationMode()), route.lease());
    }

    public static void closeQuietly(AutoCloseable lease) {
        if (lease == null) return;
        try {
            lease.close();
        } catch (Exception ignored) {
            // A failed best-effort tunnel cleanup must not mask a session shutdown error.
        }
    }

    public record RoutedConnection(ConnectionRequest request, AutoCloseable lease) {
        public RoutedConnection {
            Objects.requireNonNull(request, "request");
            lease = lease == null ? NOOP_LEASE : lease;
        }
    }
}
