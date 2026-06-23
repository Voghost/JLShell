package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.Optional;

/** 能力注册表。每个 SSH/本地会话一个实例；另有一个全局实例放无 session 能力。 */
public interface CapabilityRegistry {
    void register(Capability capability);
    void unregister(String name);
    List<CapabilitySpec> specs();
    Optional<Capability> resolve(String name);

    /** no-op 实现：register 丢弃、resolve 永远空。供 PluginContext 默认值用，避免旧插件 NPE。 */
    static CapabilityRegistry empty() { return EmptyCapabilityRegistry.INSTANCE; }
}
