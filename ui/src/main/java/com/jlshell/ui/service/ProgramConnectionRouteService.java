package com.jlshell.ui.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.ui.model.ConnectionProfile;

/** 将 Program 插件的连接前本地回环路由安全应用到一次 SSH 建连。 */
public final class ProgramConnectionRouteService {

    private static final AutoCloseable NOOP_LEASE = () -> { };
    private final ProgramPluginManager programPlugins;

    public ProgramConnectionRouteService(ProgramPluginManager programPlugins) {
        this.programPlugins = programPlugins;
    }

    public CompletableFuture<RoutedConnection> route(ConnectionProfile profile, ConnectionRequest request) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(request, "request");
        if (programPlugins == null) {
            return CompletableFuture.completedFuture(new RoutedConnection(request, NOOP_LEASE));
        }
        ConnectionRouteRequest routeRequest = new ConnectionRouteRequest(
                profile.id() == null ? "" : profile.id(), profile.projectId(), profile.displayName(),
                request.target().host(), request.target().port(), request.target().username());
        return programPlugins.connectionIntegrationRegistry().route(routeRequest)
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
