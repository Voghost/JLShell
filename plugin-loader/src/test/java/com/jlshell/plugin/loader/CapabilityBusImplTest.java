package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityBusImplTest {

    private DefaultPluginContext ctxFor(PluginManager mgr, String pluginId, String sessionId) {
        return new DefaultPluginContext(pluginId, sessionId, mgr.registryFor(sessionId),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
    }

    @Test
    void invokeRegisteredCapabilityReturnsResult() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor(mgr, "com.a", "s1");
        ctx.capabilityRegistry().register(
                Capability.builder("echo").handler((a, c) -> CompletableFuture.completedFuture(new JsonPrimitive("pong"))).build());
        // 模拟 host 已激活：把 ctx 注册进 manager 的 session bucket
        mgr.adoptContext("s1", "com.a", ctx);

        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "echo", new JsonPrimitive("ping"), "r1")).get();
        assertThat(resp.error()).isNull();
        assertThat(resp.result().getAsString()).isEqualTo("pong");
    }

    @Test
    void unknownCapabilityReturnsMethodNotFound() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "nope", null, "r1")).get();
        assertThat(resp.result()).isNull();
        assertThat(resp.error().code()).isEqualTo(-32601);
    }

    @Test
    void handlerThrowingReturnsInternalError() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor(mgr, "com.a", "s1");
        ctx.capabilityRegistry().register(
                Capability.builder("boom").handler((a, c) -> { throw new IllegalStateException("kaboom"); }).build());
        mgr.adoptContext("s1", "com.a", ctx);
        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "boom", null, "r1")).get();
        assertThat(resp.error().code()).isEqualTo(-32603);
        assertThat(resp.error().message()).isEqualTo("kaboom");
    }
}
