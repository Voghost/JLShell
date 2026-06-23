package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/**
 * capability.list method：返回某 session 的能力清单。
 *
 * <p>sessionId 为 null 表示仅全局能力。每个能力序列化为
 * {@code {name, description, requiresSession, inputSchema?}}。
 */
public class CapabilityListMethod implements MethodHandler {
    private final CapabilityBus bus;

    public CapabilityListMethod(CapabilityBus bus) {
        this.bus = bus;
    }

    @Override
    public CompletableFuture<JsonElement> handle(JsonElement params) {
        String sessionId = null;
        if (params != null && params.isJsonObject()) {
            JsonObject p = params.getAsJsonObject();
            sessionId = p.has("sessionId") && !p.get("sessionId").isJsonNull() ? p.get("sessionId").getAsString() : null;
        }
        JsonArray arr = new JsonArray();
        for (CapabilitySpec spec : bus.listCapabilities(sessionId)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", spec.name());
            o.addProperty("description", spec.description() == null ? "" : spec.description());
            o.addProperty("requiresSession", spec.requiresSession());
            if (spec.inputSchema() != null) o.add("inputSchema", spec.inputSchema());
            arr.add(o);
        }
        return CompletableFuture.completedFuture(arr);
    }
}
