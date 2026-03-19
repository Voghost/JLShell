package com.jlshell.core.service;

import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.session.SshSession;

/**
 * SSH 连接入口。
 * 该接口由 ssh 模块实现，core 仅依赖抽象。
 */
public interface ConnectionManager {

    CompletableFuture<SshSession> connect(ConnectionRequest request);
}
