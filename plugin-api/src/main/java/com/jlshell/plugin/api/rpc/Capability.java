package com.jlshell.plugin.api.rpc;

/** 一个已注册的能力。pluginId 由 host 在注册时注入，插件不必填。 */
public interface Capability {
    String pluginId();
    CapabilitySpec spec();
    CapabilityHandler handler();
    static Capability.Builder builder(String name) { return new DefaultCapability.Builder(name); }

    interface Builder {
        Builder description(String description);
        Builder inputSchema(com.google.gson.JsonObject schema);
        Builder requiresSession(boolean requires);
        Builder handler(CapabilityHandler handler);
        Capability build();
    }
}
