package com.jlshell.terminal.service;

import java.util.concurrent.CompletableFuture;

import com.jlshell.core.session.SshSession;
import com.jlshell.terminal.model.TerminalViewRequest;

/**
 * 终端视图工厂。
 * 负责把 SSH 会话转换为可嵌入 UI 的终端组件。
 */
public interface TerminalViewFactory {

    CompletableFuture<TerminalViewHandle> createTerminalView(SshSession sshSession, TerminalViewRequest request);
}
