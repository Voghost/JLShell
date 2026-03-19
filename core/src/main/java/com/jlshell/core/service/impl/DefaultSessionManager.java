package com.jlshell.core.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.exception.SessionNotFoundException;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.ConnectionManager;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.service.SessionRegistry;
import com.jlshell.core.session.SshSession;

/**
 * 默认会话管理器。
 * 该类保持轻量，只做编排与错误转换，避免演变为 God Class。
 */
public class DefaultSessionManager implements SessionManager {

    private final ConnectionManager connectionManager;
    private final SessionRegistry sessionRegistry;

    public DefaultSessionManager(ConnectionManager connectionManager, SessionRegistry sessionRegistry) {
        this.connectionManager = connectionManager;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public CompletableFuture<SshSession> openSession(ConnectionRequest request) {
        return connectionManager.connect(request)
                .thenApply(session -> {
                    // 只有连接真正成功后才注册，避免脏会话出现在 UI 列表。
                    sessionRegistry.register(session);
                    return session;
                });
    }

    @Override
    public Optional<SshSession> getSession(SessionId sessionId) {
        return sessionRegistry.find(sessionId);
    }

    @Override
    public List<SessionDescriptor> listSessions() {
        return sessionRegistry.listDescriptors();
    }

    @Override
    public CompletableFuture<Void> closeSession(SessionId sessionId) {
        SshSession session = sessionRegistry.find(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        return session.disconnect()
                // 不论底层关闭成功或失败，都从活动注册表中移除，避免 UI 悬挂。
                .whenComplete((unused, throwable) -> sessionRegistry.remove(sessionId));
    }

    @Override
    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> futures = sessionRegistry.listSessions()
                .stream()
                .map(session -> closeSession(session.sessionId()))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
}
