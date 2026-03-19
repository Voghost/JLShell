package com.jlshell.core.service;

import java.util.List;
import java.util.Optional;

import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.session.SshSession;

/**
 * 会话注册表。
 * 只负责保存和索引当前活跃会话，不处理连接建立逻辑。
 */
public interface SessionRegistry {

    void register(SshSession session);

    Optional<SshSession> find(SessionId sessionId);

    void remove(SessionId sessionId);

    List<SessionDescriptor> listDescriptors();

    List<SshSession> listSessions();
}
