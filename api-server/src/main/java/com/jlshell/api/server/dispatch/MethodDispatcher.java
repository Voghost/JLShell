package com.jlshell.api.server.dispatch;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonElement;
import com.jlshell.api.server.jsonrpc.JsonRpcError;

/** method 名 → handler 路由。未知 method 返回带 METHOD_NOT_FOUND 的 failed future。 */
public class MethodDispatcher {
    private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();

    public void register(String method, MethodHandler handler) { handlers.put(method, handler); }

    public CompletableFuture<JsonElement> dispatch(String method, JsonElement params) {
        MethodHandler h = handlers.get(method);
        if (h == null) {
            return CompletableFuture.failedFuture(new MethodNotFoundException(method));
        }
        try {
            return h.handle(params);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 未知 method 的异常，供 RpcHandler 映射成 JSON-RPC error。 */
    public static class MethodNotFoundException extends RuntimeException {
        public final int code = JsonRpcError.of(-32601, "").code();
        public MethodNotFoundException(String method) { super("method not found: " + method); }
    }
}
