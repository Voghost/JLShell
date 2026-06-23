package com.jlshell.plugin.api.rpc;

import java.util.Optional;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;

/** 能力被调用时能拿到的上下文。sessionId 为 null 表示全局能力。 */
public interface CapabilityContext {
    String sessionId();
    Optional<SshSessionContext> sshSession();
    PluginContext pluginContext();
}
