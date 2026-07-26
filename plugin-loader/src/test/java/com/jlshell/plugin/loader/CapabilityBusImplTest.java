package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.jlshell.plugin.api.security.PluginAccessDecision;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityBusImplTest {

    private DefaultPluginContext ctxFor(PluginManager mgr, String pluginId, String sessionId) {
        // Fix 1 后 registryFor 对未知 sessionId 返回共享空哨兵、不再自动建桶。
        // 这里需先确保 session 桶存在，再取桶的 registry 构造 ctx，使 ctx 的 registry
        // 与桶的 registry 是同一对象（bus.invoke 经 registryFor 读桶）。
        // adoptContext 的 contexts 是 ConcurrentHashMap 不接受 null，故用占位 ctx 先建桶。
        DefaultPluginContext placeholder = new DefaultPluginContext(pluginId, Optional.empty(), new DefaultPluginContext.Callbacks() {
            @Override public void openTab(String t, javafx.scene.Node n) {}
            @Override public void closeTab() {}
            @Override public void updateTabTitle(String t) {}
            @Override public String resolveI18n(String k, String f) { return f; }
        });
        mgr.adoptContext(sessionId, pluginId, placeholder);
        DefaultPluginContext ctx = new DefaultPluginContext(pluginId, sessionId, mgr.registryFor(sessionId),
                Optional.empty(), new DefaultPluginContext.Callbacks() {
                    @Override public void openTab(String t, javafx.scene.Node n) {}
                    @Override public void closeTab() {}
                    @Override public void updateTabTitle(String t) {}
                    @Override public String resolveI18n(String k, String f) { return f; }
                });
        mgr.adoptContext(sessionId, pluginId, ctx);
        return ctx;
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

    @Test
    void handlerCompletingExceptionallyReturnsInternalError() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor(mgr, "com.a", "s1");
        ctx.capabilityRegistry().register(
                Capability.builder("boom").handler((a, c) -> CompletableFuture.failedFuture(new IllegalStateException("async-kaboom"))).build());
        mgr.adoptContext("s1", "com.a", ctx);
        RpcResponse resp = bus.invoke(new RpcRequest("s1", "com.a", "boom", null, "r1")).get();
        assertThat(resp.error().code()).isEqualTo(-32603);
        assertThat(resp.error().message()).isEqualTo("async-kaboom");
    }

    @Test
    void deniedCapabilityIsHiddenAndCannotBeInvoked() throws Exception {
        PluginManager mgr = new PluginManager();
        CapabilityBus bus = new CapabilityBusImpl(mgr);
        DefaultPluginContext ctx = ctxFor(mgr, "com.a", "s1");
        ctx.capabilityRegistry().register(Capability.builder("paid")
                .handler((a, c) -> CompletableFuture.completedFuture(new JsonPrimitive("secret")))
                .build());
        mgr.accessController().registerTrusted("subscription", request ->
                "com.a".equals(request.pluginId()) && "paid".equals(request.capability())
                        ? PluginAccessDecision.deny("pro required")
                        : PluginAccessDecision.abstain());

        RpcResponse response = bus.invoke(
                new RpcRequest("s1", "com.a", "paid", null, "r1")).get();

        assertThat(response.error().code()).isEqualTo(-32003);
        assertThat(response.error().message()).isEqualTo("pro required");
        assertThat(bus.listCapabilities("s1")).isEmpty();
    }
}
