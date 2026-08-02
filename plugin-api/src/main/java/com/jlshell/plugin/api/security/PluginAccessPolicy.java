package com.jlshell.plugin.api.security;

/** 插件可查询的只读权限决策入口。 */
public interface PluginAccessPolicy {

    PluginAccessDecision evaluate(PluginAccessRequest request);

    static PluginAccessPolicy allowAll() {
        return request -> PluginAccessDecision.allow();
    }
}
