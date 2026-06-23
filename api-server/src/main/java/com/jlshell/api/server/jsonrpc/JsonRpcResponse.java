package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcResponse(String jsonrpc, Object id, JsonElement result, JsonRpcError error) {
    public static JsonRpcResponse ok(Object id, JsonElement result) { return new JsonRpcResponse("2.0", id, result, null); }
    public static JsonRpcResponse err(Object id, JsonRpcError e) { return new JsonRpcResponse("2.0", id, null, e); }
}
