package com.jlshell.plugin.loader;

import java.util.List;
import java.util.Optional;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/**
 * 暴露给单个插件的 registry 视图：把 register(Capability) 绑定到该插件的 id。
 * 这样插件调 ctx.capabilityRegistry().register(cap) 时自动带上自己的 pluginId。
 */
public class PluginCapabilityRegistryView implements CapabilityRegistry {
    private final CapabilityRegistryImpl delegate;
    private final String pluginId;

    public PluginCapabilityRegistryView(CapabilityRegistryImpl delegate, String pluginId) {
        this.delegate = delegate;
        this.pluginId = pluginId;
    }

    @Override public void register(Capability capability) { delegate.register(pluginId, capability); }
    @Override public void unregister(String name) { delegate.unregister(pluginId, name); }
    @Override public List<CapabilitySpec> specs() { return delegate.specs(pluginId); }
    @Override public Optional<Capability> resolve(String name) { return delegate.resolve(pluginId, name); }
}
