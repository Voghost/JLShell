package com.jlshell.core.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionRegistry;
import com.jlshell.core.session.SshSession;

/**
 * 线程安全的内存会话注册表。
 * 桌面应用单机运行场景下足够轻量，后续也可平滑替换为持久化实现。
 */
public class InMemorySessionRegistry implements SessionRegistry {

    private final ConcurrentHashMap<SessionId, SshSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void register(SshSession session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<SshSession> find(SessionId sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(SessionId sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public List<SessionDescriptor> listDescriptors() {
        return sessions.values()
                .stream()
                .map(SshSession::descriptor)
                // 统一按连接时间排序，便于 UI 稳定展示。
                .sorted((left, right) -> left.connectedAt().compareTo(right.connectedAt()))
                .toList();
    }

    @Override
    public List<SshSession> listSessions() {
        return List.copyOf(sessions.values());
    }
}
