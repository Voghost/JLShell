package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.Optional;

/** CapabilityRegistry.empty() 的单例 no-op 实现。 */
final class EmptyCapabilityRegistry implements CapabilityRegistry {
    static final EmptyCapabilityRegistry INSTANCE = new EmptyCapabilityRegistry();
    private EmptyCapabilityRegistry() {}
    @Override public void register(Capability c) {}
    @Override public void unregister(String name) {}
    @Override public List<CapabilitySpec> specs() { return List.of(); }
    @Override public Optional<Capability> resolve(String name) { return Optional.empty(); }
}
