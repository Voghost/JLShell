package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcError(int code, String message, JsonElement data) {
    public static JsonRpcError of(int code, String message) { return new JsonRpcError(code, message, null); }
}
