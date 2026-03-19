package com.jlshell.plugin.api;

import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.capability.LogViewer;
import com.jlshell.plugin.api.capability.ServerStatusProvider;

/**
 * SSH capability facade exposed to plugins.
 * Hides the underlying SSHJ implementation details.
 */
public interface SshSessionContext {

    String sessionId();

    String displayName();

    String host();

    int port();

    String username();

    CommandExecutor commandExecutor();

    FileExplorer fileExplorer();

    LogViewer logViewer();

    ServerStatusProvider serverStatus();
}
