package com.jlshell.plugin.loader;

import java.util.Optional;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityContext;

/** CapabilityContext 默认实现。 */
public class CapabilityContextImpl implements CapabilityContext {
    private final String sessionId;
    private final Optional<SshSessionContext> sshSession;
    private final PluginContext pluginContext;

    public CapabilityContextImpl(String sessionId, Optional<SshSessionContext> sshSession, PluginContext pluginContext) {
        this.sessionId = sessionId;
        this.sshSession = sshSession;
        this.pluginContext = pluginContext;
    }

    @Override public String sessionId() { return sessionId; }
    @Override public Optional<SshSessionContext> sshSession() { return sshSession; }
    @Override public PluginContext pluginContext() { return pluginContext; }
}
