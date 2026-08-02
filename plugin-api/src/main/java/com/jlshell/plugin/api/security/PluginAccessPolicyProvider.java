package com.jlshell.plugin.api.security;

/** 只有宿主判定为受信任的 Program 插件才会被注册为全局权限提供者。 */
public interface PluginAccessPolicyProvider {

    default int priority() {
        return 0;
    }

    PluginAccessDecision evaluate(PluginAccessRequest request);
}
