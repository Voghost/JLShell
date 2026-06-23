package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * RPC 错误。跨 HTTP 边界时用，message 为英文短描述（机器消费，不做 i18n）。
 */
public record RpcError(int code, String message, JsonElement data) {
    public static RpcError of(int code, String message) {
        return new RpcError(code, message, null);
    }
}
