package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.jlshell.plugin.api.rpc.SessionPluginActivator;

/**
 * capability.invoke method：把 JSON-RPC params 透传成 RpcRequest 调 CapabilityBus。
 *
 * <p>能力层返回的 {@link RpcResponse#error()} 不抛异常——这里用 thenCompose 把它转成
 * failed future（携带 {@link CapabilityErrorException}），让 RpcHandler 据此把能力层错误码
 * 原样带回 HTTP 响应。
 */
public class CapabilityInvokeMethod implements MethodHandler {
    private static final int METHOD_NOT_FOUND = -32601;
    private final CapabilityBus bus;
    private final SessionPluginActivator sessionPluginActivator;

    public CapabilityInvokeMethod(CapabilityBus bus) {
        this(bus, SessionPluginActivator.noop());
    }

    public CapabilityInvokeMethod(CapabilityBus bus, SessionPluginActivator sessionPluginActivator) {
        this.bus = bus;
        this.sessionPluginActivator = sessionPluginActivator == null
                ? SessionPluginActivator.noop() : sessionPluginActivator;
    }

    @Override
    public CompletableFuture<JsonElement> handle(JsonElement params) {
        JsonObject p = params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject();
        String sessionId = p.has("sessionId") && !p.get("sessionId").isJsonNull() ? p.get("sessionId").getAsString() : null;
        String pluginId = p.has("pluginId") && !p.get("pluginId").isJsonNull() ? p.get("pluginId").getAsString() : null;
        String capability = p.has("capability") && !p.get("capability").isJsonNull() ? p.get("capability").getAsString() : null;
        JsonElement args = p.has("args") ? p.get("args") : null;
        String requestId = p.has("requestId") && !p.get("requestId").isJsonNull() ? p.get("requestId").getAsString() : null;
        if (pluginId == null || capability == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("pluginId and capability required"));
        }
        RpcRequest req = new RpcRequest(sessionId, pluginId, capability, args, requestId);
        return invokeWithActivation(req).thenCompose(r -> {
            if (r.error() != null) {
                return CompletableFuture.failedFuture(new CapabilityErrorException(r.error().code(), r.error().message()));
            }
            return CompletableFuture.completedFuture(r.result());
        });
    }

    /**
     * 会话插件默认由 UI 按需打开。API 首次调用找不到能力时，静默激活目标插件后重试，
     * 因而 Agent 不必要求用户先打开插件标签页。全局程序插件在应用启动时已自动激活，
     * sessionId 为 null 时不会触发该路径。
     */
    private CompletableFuture<RpcResponse> invokeWithActivation(RpcRequest request) {
        return bus.invoke(request).thenCompose(response -> {
            if (request.sessionId() == null || response.error() == null
                    || response.error().code() != METHOD_NOT_FOUND) {
                return CompletableFuture.completedFuture(response);
            }
            return sessionPluginActivator.activate(request.sessionId(), request.pluginId())
                    .thenCompose(ignored -> bus.invoke(request));
        });
    }

    /**
     * 能力层错误包装异常：携带能力层 RpcError.code，供 RpcHandler 直接映射成
     * JSON-RPC error code（避免吞掉 capability 层自定义错误码）。
     */
    public static class CapabilityErrorException extends RuntimeException {
        public final int code;

        public CapabilityErrorException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
