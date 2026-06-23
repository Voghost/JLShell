package com.jlshell.plugin.loader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;

/** 单个会话下激活的插件集合 + 该会话的能力注册表。 */
class SessionPluginSet {
    final String sessionId;
    final CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
    final Map<String, JlShellPlugin> plugins = new ConcurrentHashMap<>();
    final Map<String, PluginContext> contexts = new ConcurrentHashMap<>();

    SessionPluginSet(String sessionId) { this.sessionId = sessionId; }
}
