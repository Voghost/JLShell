package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * RPC 响应。成功时 error=null；失败时 result=null。
 */
public record RpcResponse(JsonElement result, RpcError error) {
    public static RpcResponse ok(JsonElement result) { return new RpcResponse(result, null); }
    public static RpcResponse error(RpcError e) { return new RpcResponse(null, e); }
}
