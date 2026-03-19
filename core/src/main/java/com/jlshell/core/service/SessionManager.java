package com.jlshell.core.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.session.SshSession;

/**
 * 会话编排服务。
 * 对外提供“打开、查询、关闭”能力，对内委托 ConnectionManager 和 SessionRegistry。
 */
public interface SessionManager {

    CompletableFuture<SshSession> openSession(ConnectionRequest request);

    Optional<SshSession> getSession(SessionId sessionId);

    List<SessionDescriptor> listSessions();

    CompletableFuture<Void> closeSession(SessionId sessionId);

    CompletableFuture<Void> closeAll();
}
