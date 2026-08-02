package com.jlshell.program.plugin.loader;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.api.session.ProgramSessionController;
import com.jlshell.plugin.api.session.ProgramSessionIntegration;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginManager;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Program 插件会话贡献的宿主注册表。
 *
 * <p>每个 Program 插件只能注册一个会话贡献。活动实例按 Program 插件 id 和 sessionId
 * 隔离；插件停用、贡献注销或会话关闭都会回收上下文、页面和会话能力。</p>
 */
public final class ProgramSessionIntegrationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProgramSessionIntegrationRegistry.class);

    private final PluginManager pluginManager;
    private final Map<String, RegisteredContribution> contributions = new ConcurrentHashMap<>();
    private final Map<ActivationKey, ActiveContribution> active = new ConcurrentHashMap<>();
    private final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper();

    public ProgramSessionIntegrationRegistry(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
    }

    public ProgramSessionIntegration scoped(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return new ScopedIntegration(pluginId);
    }

    public List<RegisteredContribution> contributions(SshSessionContext session) {
        return contributions.values().stream()
                .filter(entry -> entry.contribution().supports(session))
                .sorted(java.util.Comparator.comparing(RegisteredContribution::pluginId))
                .toList();
    }

    public ReadOnlyLongProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /** 激活用户在当前 SSH 会话中选择的 Program 插件贡献。 */
    public boolean activate(String pluginId, String sessionId, PluginContext context) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(context, "context");
        RegisteredContribution registered = contributions.get(pluginId);
        SshSessionContext ssh = context.sshSession().orElse(null);
        if (registered == null || ssh == null || !registered.contribution().supports(ssh)) {
            disposeUnusedContext(context);
            return false;
        }

        ActivationKey key = new ActivationKey(sessionId, pluginId);
        if (active.containsKey(key)) {
            disposeUnusedContext(context);
            return false;
        }

        pluginManager.adoptContext(sessionId, pluginId, context);
        try {
            ProgramSessionController controller = registered.contribution().activate(context);
            ActiveContribution value = new ActiveContribution(
                    registered, context, controller == null ? ProgramSessionController.noop() : controller);
            ActiveContribution existing = active.putIfAbsent(key, value);
            if (existing != null) {
                closeActive(key, value);
                return false;
            }
            return true;
        } catch (RuntimeException | Error error) {
            cleanupContext(sessionId, pluginId, context);
            throw error;
        }
    }

    public boolean isActive(String sessionId, String pluginId) {
        return active.containsKey(new ActivationKey(sessionId, pluginId));
    }

    public void deactivate(String sessionId, String pluginId) {
        ActivationKey key = new ActivationKey(sessionId, pluginId);
        ActiveContribution value = active.remove(key);
        if (value != null) {
            closeActive(key, value);
        }
    }

    public void deactivateSession(String sessionId) {
        active.forEach((key, value) -> {
            if (key.sessionId().equals(sessionId) && active.remove(key, value)) {
                closeActive(key, value);
            }
        });
    }

    public void clearForPlugin(String pluginId) {
        RegisteredContribution registered = contributions.remove(pluginId);
        if (registered == null) {
            return;
        }
        active.forEach((key, value) -> {
            if (key.pluginId().equals(pluginId) && active.remove(key, value)) {
                closeActive(key, value);
            }
        });
        revision.set(revision.get() + 1);
    }

    private void closeActive(ActivationKey key, ActiveContribution value) {
        try {
            value.controller().close();
        } catch (RuntimeException error) {
            log.warn("Failed to close Program session contribution {} in {}",
                    key.pluginId(), key.sessionId(), error);
        } finally {
            cleanupContext(key.sessionId(), key.pluginId(), value.context());
        }
    }

    private void cleanupContext(String sessionId, String pluginId, PluginContext context) {
        pluginManager.registryForSession(sessionId).clearForPlugin(pluginId);
        pluginManager.releaseContext(sessionId, pluginId, context);
        disposeUnusedContext(context);
    }

    private static void disposeUnusedContext(PluginContext context) {
        if (!(context instanceof DefaultPluginContext defaultContext)) {
            return;
        }
        try {
            defaultContext.closeTab();
        } catch (RuntimeException error) {
            log.warn("Failed to close Program session contribution tab", error);
        } finally {
            defaultContext.disposeBindings();
        }
    }

    public record RegisteredContribution(String pluginId, ProgramSessionContribution contribution) {
        public RegisteredContribution {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(contribution, "contribution");
        }
    }

    private record ActivationKey(String sessionId, String pluginId) {
    }

    private record ActiveContribution(RegisteredContribution registered,
                                      PluginContext context,
                                      ProgramSessionController controller) {
    }

    private final class ScopedIntegration implements ProgramSessionIntegration, AutoCloseable {
        private final String pluginId;

        private ScopedIntegration(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override public boolean available() { return true; }

        @Override
        public Registration register(ProgramSessionContribution contribution) {
            Objects.requireNonNull(contribution, "contribution");
            RegisteredContribution replacement = new RegisteredContribution(pluginId, contribution);
            RegisteredContribution previous = contributions.put(pluginId, replacement);
            if (previous != null) {
                active.forEach((key, value) -> {
                    if (key.pluginId().equals(pluginId) && active.remove(key, value)) {
                        closeActive(key, value);
                    }
                });
            }
            revision.set(revision.get() + 1);
            return () -> {
                if (contributions.remove(pluginId, replacement)) {
                    active.forEach((key, value) -> {
                        if (key.pluginId().equals(pluginId) && active.remove(key, value)) {
                            closeActive(key, value);
                        }
                    });
                    revision.set(revision.get() + 1);
                }
            };
        }

        @Override
        public void close() {
            clearForPlugin(pluginId);
        }
    }
}
