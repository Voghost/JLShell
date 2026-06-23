package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonElement;

/**
 * 统一 RPC 请求信封。plugin↔plugin 与外部 HTTP 共用。
 * sessionId 为 null 表示调用全局（无 session）能力。
 */
public record RpcRequest(String sessionId, String pluginId, String capability,
                         JsonElement args, String requestId) {}
