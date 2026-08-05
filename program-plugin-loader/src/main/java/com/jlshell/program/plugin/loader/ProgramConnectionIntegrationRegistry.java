package com.jlshell.program.plugin.loader;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.connection.ConnectionRoute;
import com.jlshell.plugin.api.connection.ConnectionRouteRequest;
import com.jlshell.plugin.api.connection.ProgramConnectionIntegration;
import com.jlshell.plugin.api.connection.ProgramConnectionRouteContribution;
import com.jlshell.plugin.api.lifecycle.Registration;

/**
 * Program 插件连接前路由注册表。
 *
 * <p>一个保存的 SSH 连接最多只能由一个 Program 插件接管，避免多个插件串联改写目标。
 * 路由结果只能是本地回环地址，具体校验也在 SDK {@link ConnectionRoute} 中执行。</p>
 */
public final class ProgramConnectionIntegrationRegistry {

    private final Map<String, RegisteredContribution> contributions = new ConcurrentHashMap<>();

    public ProgramConnectionIntegration scoped(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return new ScopedIntegration(pluginId);
    }

    public CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request) {
        Objects.requireNonNull(request, "request");
        List<RegisteredContribution> matches;
        try {
            matches = contributions.values().stream()
                    .filter(entry -> entry.contribution().supports(request))
                    .sorted(java.util.Comparator.comparing(RegisteredContribution::pluginId))
                    .toList();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (matches.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (matches.size() > 1) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "multiple Program plugins requested to route this SSH connection"));
        }
        try {
            CompletableFuture<ConnectionRoute> routed = matches.getFirst().contribution().route(request);
            if (routed == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("connection route future is required"));
            }
            return routed.thenApply(route -> {
                if (route == null) throw new IllegalStateException("connection route is required");
                return route;
            });
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public void clearForPlugin(String pluginId) {
        contributions.remove(pluginId);
    }

    public record RegisteredContribution(String pluginId, ProgramConnectionRouteContribution contribution) {
        public RegisteredContribution {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(contribution, "contribution");
        }
    }

    private final class ScopedIntegration implements ProgramConnectionIntegration, AutoCloseable {
        private final String pluginId;

        private ScopedIntegration(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override public boolean available() { return true; }

        @Override
        public Registration register(ProgramConnectionRouteContribution contribution) {
            Objects.requireNonNull(contribution, "contribution");
            RegisteredContribution registered = new RegisteredContribution(pluginId, contribution);
            contributions.put(pluginId, registered);
            return () -> contributions.remove(pluginId, registered);
        }

        @Override
        public void close() {
            clearForPlugin(pluginId);
        }
    }
}
