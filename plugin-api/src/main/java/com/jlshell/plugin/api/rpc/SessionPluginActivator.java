package com.jlshell.plugin.api.rpc;

import java.util.concurrent.CompletableFuture;

/**
 * 按需激活或清理某个 SSH 会话中的会话插件。
 *
 * <p>外部 API 和具体的 JavaFX/插件加载实现通过这个小契约衔接，避免 API server
 * 反向依赖 UI 或 plugin-loader。
 */
public interface SessionPluginActivator {

    CompletableFuture<Void> activate(String sessionId, String pluginId);

    default CompletableFuture<Void> deactivate(String sessionId) {
        return CompletableFuture.completedFuture(null);
    }

    static SessionPluginActivator noop() {
        return new SessionPluginActivator() {
            @Override
            public CompletableFuture<Void> activate(String sessionId, String pluginId) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
