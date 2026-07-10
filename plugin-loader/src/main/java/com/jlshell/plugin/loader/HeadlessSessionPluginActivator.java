package com.jlshell.plugin.loader;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.SessionPluginActivator;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.sftp.service.SftpService;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 供外部 API 使用的会话插件激活器：在 JavaFX 线程中调用插件生命周期，
 * 但不给用户创建/选中任何插件标签页。
 */
public final class HeadlessSessionPluginActivator implements SessionPluginActivator {

    private static final Logger log = LoggerFactory.getLogger(HeadlessSessionPluginActivator.class);

    private final PluginManager pluginManager;
    private final SessionManager sessionManager;
    private final SftpService sftpService;
    private final CapabilityBus capabilityBus;
    private final Function<String, PluginStorage> storageFactory;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activationFutures = new ConcurrentHashMap<>();

    public HeadlessSessionPluginActivator(PluginManager pluginManager, SessionManager sessionManager,
                                          SftpService sftpService, CapabilityBus capabilityBus,
                                          Function<String, PluginStorage> storageFactory) {
        this.pluginManager = pluginManager;
        this.sessionManager = sessionManager;
        this.sftpService = sftpService;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
    }

    @Override
    public CompletableFuture<Void> activate(String sessionId, String pluginId) {
        if (sessionId == null || pluginId == null) return CompletableFuture.completedFuture(null);
        String key = sessionId + '\u0000' + pluginId;
        CompletableFuture<Void> activation = activationFutures.computeIfAbsent(
                key, ignored -> startActivation(sessionId, pluginId));
        // 仅把它当作“正在激活”的去重锁；成功后也要移除，才能在用户关闭插件后再次按需激活。
        activation.whenComplete((unused, error) -> activationFutures.remove(key, activation));
        return activation;
    }

    @Override
    public CompletableFuture<Void> deactivate(String sessionId) {
        if (sessionId == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable cleanup = () -> {
            try {
                pluginManager.deactivateSession(sessionId);
                activationFutures.keySet().removeIf(key -> key.startsWith(sessionId + '\u0000'));
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        if (Platform.isFxApplicationThread()) {
            cleanup.run();
        } else {
            try {
                Platform.runLater(cleanup);
            } catch (IllegalStateException platformUnavailable) {
                result.completeExceptionally(new IllegalStateException(
                        "JLShell JavaFX runtime is not available for plugin cleanup", platformUnavailable));
            }
        }
        return result;
    }

    private CompletableFuture<Void> startActivation(String sessionId, String pluginId) {
        if (pluginManager.isPluginActive(sessionId, pluginId)) {
            return CompletableFuture.completedFuture(null);
        }
        PluginDescriptor descriptor = pluginManager.getAvailablePlugins().stream()
                .filter(candidate -> candidate.id().equals(pluginId))
                .findFirst()
                .orElse(null);
        if (descriptor == null) {
            // 保持 CapabilityBus 的标准 -32601 语义，不把“未知插件”改成 API 参数错误。
            return CompletableFuture.completedFuture(null);
        }

        final SshSession session;
        try {
            session = sessionManager.getSession(new SessionId(java.util.UUID.fromString(sessionId))).orElse(null);
        } catch (IllegalArgumentException invalidId) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid sessionId: " + sessionId));
        }
        if (session == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("session not found: " + sessionId));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable activation = () -> {
            try {
                if (!pluginManager.isPluginActive(sessionId, pluginId)) {
                    activateOnFxThread(descriptor, sessionId, session);
                }
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        if (Platform.isFxApplicationThread()) {
            activation.run();
        } else {
            try {
                Platform.runLater(activation);
            } catch (IllegalStateException platformUnavailable) {
                result.completeExceptionally(new IllegalStateException(
                        "JLShell JavaFX runtime is not available for plugin activation", platformUnavailable));
            }
        }
        return result;
    }

    private void activateOnFxThread(PluginDescriptor descriptor, String sessionId, SshSession session) {
        Optional<SshSessionContext> sshContext = Optional.of(new SshSessionContextAdapter(session, sftpService));
        PluginStorage storage = storageFactory == null ? null : storageFactory.apply(descriptor.id());
        DefaultPluginContext context = new DefaultPluginContext(
                descriptor.id(), sessionId, pluginManager.registryForSession(sessionId), capabilityBus,
                storage, sshContext, new HeadlessCallbacks());
        context.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
        context.writableLocaleProperty().bind(pluginManager.localeProperty());
        pluginManager.adoptContext(sessionId, descriptor.id(), context);
        pluginManager.activatePlugin(descriptor.id(), context);
        log.info("Headlessly activated session plugin {} for session {}", descriptor.id(), sessionId);
    }

    /** API 驱动时不展示 UI；仍提供安全的 i18n fallback，供非 UI 插件使用。 */
    private static final class HeadlessCallbacks implements DefaultPluginContext.Callbacks {
        @Override public void openTab(String title, javafx.scene.Node content) {}
        @Override public void closeTab() {}
        @Override public void updateTabTitle(String title) {}
        @Override public String resolveI18n(String key, String fallback) { return fallback; }
    }
}
