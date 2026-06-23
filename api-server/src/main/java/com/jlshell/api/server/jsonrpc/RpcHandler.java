package com.jlshell.api.server.jsonrpc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import com.google.gson.JsonNull;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.jlshell.api.server.dispatch.CapabilityInvokeMethod.CapabilityErrorException;
import com.jlshell.api.server.dispatch.MethodDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /rpc 端点：bearer token 鉴权 + POST + JSON → dispatch → 编码响应。
 *
 * <p>同步路径（405 / 401 / parse error / missing method）直接写回响应；
 * 异步路径（dispatch.whenComplete）从 future 完成线程写回——
 * {@code com.sun.net.httpserver} 允许在不同线程发送唯一一次响应，
 * 前提是 exchange 未被提前 close。{@link #write} 是唯一会 close exchange 的地方，
 * 保证每个请求恰好 close 一次。
 */
public class RpcHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(RpcHandler.class);
    private final String token;
    private final MethodDispatcher dispatcher;

    public RpcHandler(String token, MethodDispatcher dispatcher) {
        this.token = token;
        this.dispatcher = dispatcher;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, JsonRpcCodec.encode(JsonRpcResponse.err(null,
                        JsonRpcError.of(JsonRpcCodec.INVALID_REQUEST, "POST required"))));
                return;
            }
            if (!checkToken(exchange)) {
                write(exchange, 401, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"unauthorized\"}}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonRpcRequest req;
            try {
                req = JsonRpcCodec.parse(body);
            } catch (Exception e) {
                write(exchange, 200, JsonRpcCodec.encode(JsonRpcResponse.err(null,
                        JsonRpcError.of(JsonRpcCodec.PARSE_ERROR, "parse error: " + e.getMessage()))));
                return;
            }
            if (req.method() == null) {
                write(exchange, 200, JsonRpcCodec.encode(JsonRpcResponse.err(req.id(),
                        JsonRpcError.of(JsonRpcCodec.INVALID_REQUEST, "method required"))));
                return;
            }
            dispatcher.dispatch(req.method(), req.params())
                    .whenComplete((result, err) -> {
                        try {
                            String resp;
                            if (err != null) {
                                int code = mapErrorCode(err);
                                String msg = rootMessage(err);
                                resp = JsonRpcCodec.encode(JsonRpcResponse.err(req.id(), JsonRpcError.of(code, msg)));
                            } else {
                                resp = JsonRpcCodec.encode(JsonRpcResponse.ok(req.id(),
                                        result == null ? JsonNull.INSTANCE : result));
                            }
                            write(exchange, 200, resp);
                        } catch (IOException ioe) {
                            log.warn("Failed to write RPC response", ioe);
                        } catch (Throwable t) {
                            log.error("Unexpected error writing RPC response", t);
                            try { write(exchange, 500,
                                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
                            } catch (IOException ignored) {}
                        }
                    });
        } catch (Exception e) {
            log.error("RPC handler error", e);
            write(exchange, 500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
        }
    }

    /**
     * 把 dispatch 抛出的异常映射成 JSON-RPC error code。
     * 优先识别被 CompletionException/ExecutionException 包装的根因。
     */
    private static int mapErrorCode(Throwable err) {
        Throwable cause = unwrap(err);
        if (cause instanceof MethodDispatcher.MethodNotFoundException) return JsonRpcCodec.METHOD_NOT_FOUND;
        if (cause instanceof CapabilityErrorException c) return c.code;
        if (cause instanceof IllegalArgumentException) return JsonRpcCodec.INVALID_PARAMS;
        return JsonRpcCodec.INTERNAL_ERROR;
    }

    /** 取根因 message；unwrap CompletionException/ExecutionException 包装层。 */
    private static String rootMessage(Throwable err) {
        Throwable cause = unwrap(err);
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg;
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            Throwable c = t.getCause();
            if (c == null) return t;
            t = c;
        }
        return t;
    }

    private boolean checkToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth != null && ("Bearer " + token).equals(auth);
    }

    /**
     * 写响应并 close exchange。所有响应路径（同步 + 异步）都走这里，
     * 保证 exchange 恰好被 close 一次。
     */
    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
