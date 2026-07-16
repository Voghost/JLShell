package com.jlshell.api.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.program.api.DefaultProgramApiRegistry;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiRegistry;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.SessionPluginActivator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiServer 真实 HTTP 往返测试：用 {@code java.net.http.HttpClient} POST /rpc，
 * 覆盖授权、401、未知 method、capability.invoke 四条路径。
 *
 * <p>端口 0 由 OS 自动选空闲端口；{@link HttpClient#send} 是阻塞调用，
 * 自然等待异步 whenComplete 写回响应，规避异步写时序问题。
 */
class ApiServerTest {

    private ApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    private ProgramApiRegistry stubProgramApi() {
        DefaultProgramApiRegistry registry = new DefaultProgramApiRegistry();
        registry.register(ProgramApiCatalog.SESSION_CONNECT,
                p -> CompletableFuture.completedFuture(new JsonPrimitive("sid-1")));
        registry.register(ProgramApiCatalog.SESSION_DISCONNECT,
                p -> CompletableFuture.completedFuture(JsonNull.INSTANCE));
        registry.register(ProgramApiCatalog.SESSION_LIST,
                p -> CompletableFuture.completedFuture(new JsonArray()));
        registry.register(ProgramApiCatalog.SESSION_INFO,
                p -> CompletableFuture.completedFuture(new JsonObject()));
        registry.register(ProgramApiCatalog.COMMAND_RUN,
                p -> CompletableFuture.completedFuture(new JsonObject()));
        registry.register(ProgramApiCatalog.API_TOKEN,
                p -> CompletableFuture.completedFuture(new JsonPrimitive("tok")));
        registry.register(ProgramApiCatalog.API_METHODS,
                p -> CompletableFuture.completedFuture(new JsonArray()));
        return registry;
    }

    private CapabilityBus stubBus() {
        return new CapabilityBus() {
            @Override public CompletableFuture<RpcResponse> invoke(RpcRequest r) {
                return CompletableFuture.completedFuture(RpcResponse.ok(new JsonPrimitive("echoed")));
            }
            @Override public java.util.List<CapabilitySpec> listCapabilities(String sid) { return java.util.List.of(); }
        };
    }

    private void startServer() throws Exception {
        server = new ApiServer(new ApiServerConfig(0, "secret-token", true), stubBus(), stubProgramApi());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/rpc"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void authorizedRequestReturnsResult() throws Exception {
        startServer();
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "session.connect");
        body.add("params", new JsonObject());
        HttpResponse<String> r = post("secret-token", body.toString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"result\":\"sid-1\"");
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        startServer();
        HttpResponse<String> r = post("wrong", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session.list\"}");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        startServer();
        HttpResponse<String> r = post("secret-token", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}");
        assertThat(r.body()).contains("-32601");
    }

    @Test
    void capabilityInvokePassesThrough() throws Exception {
        startServer();
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "s1");
        params.addProperty("pluginId", "com.a");
        params.addProperty("capability", "echo");
        params.add("args", new JsonPrimitive("hi"));
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 3);
        body.addProperty("method", "capability.invoke");
        body.add("params", params);
        HttpResponse<String> r = post("secret-token", body.toString());
        assertThat(r.body()).contains("\"result\":\"echoed\"");
    }

    @Test
    void capabilityInvokeHeadlesslyActivatesMissingSessionPluginThenRetries() throws Exception {
        AtomicBoolean activated = new AtomicBoolean();
        AtomicInteger activationCalls = new AtomicInteger();
        CapabilityBus bus = new CapabilityBus() {
            @Override public CompletableFuture<RpcResponse> invoke(RpcRequest request) {
                if (!activated.get()) {
                    return CompletableFuture.completedFuture(RpcResponse.error(
                            RpcError.of(-32601, "capability not found")));
                }
                return CompletableFuture.completedFuture(RpcResponse.ok(new JsonPrimitive("after-activation")));
            }
            @Override public java.util.List<CapabilitySpec> listCapabilities(String sid) { return java.util.List.of(); }
        };
        SessionPluginActivator activator = new SessionPluginActivator() {
            @Override public CompletableFuture<Void> activate(String sessionId, String pluginId) {
                activationCalls.incrementAndGet();
                activated.set(true);
                return CompletableFuture.completedFuture(null);
            }
        };
        server = new ApiServer(new ApiServerConfig(0, "secret-token", true), bus, stubProgramApi(), activator);
        server.start();

        HttpResponse<String> response = post("secret-token", """
                {"jsonrpc":"2.0","id":4,"method":"capability.invoke",
                 "params":{"sessionId":"s1","pluginId":"com.a","capability":"echo"}}
                """);

        assertThat(response.body()).contains("\"result\":\"after-activation\"");
        assertThat(activationCalls).hasValue(1);
    }
}
