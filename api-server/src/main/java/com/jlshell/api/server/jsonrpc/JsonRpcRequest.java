package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonElement;

public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonElement params) {}
