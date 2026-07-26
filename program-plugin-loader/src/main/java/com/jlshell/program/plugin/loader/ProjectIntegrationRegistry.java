package com.jlshell.program.plugin.loader;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import com.jlshell.plugin.api.project.ProjectIntegration;

/** 进程级项目创建贡献注册表。 */
public final class ProjectIntegrationRegistry {

    private static final ProjectIntegrationRegistry SHARED = new ProjectIntegrationRegistry();

    private final ConcurrentHashMap<String, RegisteredContribution> contributions = new ConcurrentHashMap<>();

    public static ProjectIntegrationRegistry shared() {
        return SHARED;
    }

    public ProjectIntegration scoped(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return new ScopedProjectIntegration(pluginId);
    }

    public List<RegisteredContribution> contributions() {
        return contributions.values().stream()
                .sorted(Comparator
                        .comparingInt((RegisteredContribution item) -> item.contribution().order())
                        .thenComparing(RegisteredContribution::pluginId)
                        .thenComparing(item -> item.contribution().id()))
                .toList();
    }

    public void clearForPlugin(String pluginId) {
        contributions.entrySet().removeIf(entry -> entry.getValue().pluginId().equals(pluginId));
    }

    private Registration register(String pluginId, ProjectCreationContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.id() == null || contribution.id().isBlank()) {
            throw new IllegalArgumentException("project contribution id required");
        }
        String key = pluginId + "/" + contribution.id();
        RegisteredContribution registered = new RegisteredContribution(pluginId, contribution);
        contributions.put(key, registered);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                contributions.remove(key, registered);
            }
        };
    }

    public record RegisteredContribution(String pluginId, ProjectCreationContribution contribution) {
    }

    private final class ScopedProjectIntegration implements ProjectIntegration, AutoCloseable {
        private final String pluginId;

        private ScopedProjectIntegration(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override public boolean available() { return true; }

        @Override
        public Registration register(ProjectCreationContribution contribution) {
            return ProjectIntegrationRegistry.this.register(pluginId, contribution);
        }

        @Override public void close() {
            clearForPlugin(pluginId);
        }
    }
}
