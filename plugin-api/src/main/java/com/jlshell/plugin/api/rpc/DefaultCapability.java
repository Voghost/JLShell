package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;

/** Capability 的默认实现 + Builder。 */
final class DefaultCapability implements Capability {
    private final String pluginId;
    private final CapabilitySpec spec;
    private final CapabilityHandler handler;

    DefaultCapability(String pluginId, CapabilitySpec spec, CapabilityHandler handler) {
        this.pluginId = pluginId;
        this.spec = spec;
        this.handler = handler;
    }

    @Override public String pluginId() { return pluginId; }
    @Override public CapabilitySpec spec() { return spec; }
    @Override public CapabilityHandler handler() { return handler; }

    static final class Builder implements Capability.Builder {
        private final String name;
        private String description = "";
        private JsonObject inputSchema = null;
        private boolean requiresSession = false;
        private CapabilityHandler handler;

        Builder(String name) { this.name = name; }

        @Override public Builder description(String d) { this.description = d; return this; }
        @Override public Builder inputSchema(JsonObject s) { this.inputSchema = s; return this; }
        @Override public Builder requiresSession(boolean r) { this.requiresSession = r; return this; }
        @Override public Builder handler(CapabilityHandler h) { this.handler = h; return this; }

        @Override
        public Capability build() {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("capability name required");
            if (handler == null) throw new IllegalArgumentException("handler required");
            return new DefaultCapability(null, new CapabilitySpec(name, description, inputSchema, requiresSession), handler);
        }
    }
}
