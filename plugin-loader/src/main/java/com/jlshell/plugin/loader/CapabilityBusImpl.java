package com.jlshell.plugin.loader;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityContext;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.api.rpc.RpcError;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/**
 * CapabilityBus 实现：按 (sessionId, pluginId, capability) 路由。
 * 依赖 PluginManager 的 per-session registry。
 */
public class CapabilityBusImpl implements CapabilityBus {

    private static final int CODE_METHOD_NOT_FOUND = -32601;
    private static final int CODE_INTERNAL = -32603;

    private final PluginManager pluginManager;

    public CapabilityBusImpl(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public CompletableFuture<RpcResponse> invoke(RpcRequest req) {
        if (req.pluginId() == null || req.capability() == null) {
            return CompletableFuture.completedFuture(
                    RpcResponse.error(RpcError.of(CODE_METHOD_NOT_FOUND, "pluginId and capability required")));
        }
        CapabilityRegistryImpl reg = pluginManager.registryFor(req.sessionId());
        Capability cap = reg.resolve(req.pluginId(), req.capability()).orElse(null);
        if (cap == null) {
            return CompletableFuture.completedFuture(RpcResponse.error(
                    RpcError.of(CODE_METHOD_NOT_FOUND,
                            "capability not found: " + req.pluginId() + "/" + req.capability())));
        }
        // 找到该插件的 PluginContext 以构造 CapabilityContext
        PluginContext pluginCtx = pluginManager.contextFor(req.sessionId(), req.pluginId());
        Optional<SshSessionContext> ssh = (pluginCtx instanceof DefaultPluginContext dpc)
                ? dpc.sshSession() : Optional.empty();
        CapabilityContext capCtx = new CapabilityContextImpl(req.sessionId(), ssh, pluginCtx);
        JsonElement args = req.args() == null ? JsonNull.INSTANCE : req.args();
        try {
            return cap.handler().invoke(args, capCtx)
                    .thenApply(RpcResponse::ok)
                    .exceptionally(t -> RpcResponse.error(
                            RpcError.of(CODE_INTERNAL,
                                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(RpcResponse.error(
                    RpcError.of(CODE_INTERNAL,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    @Override
    public List<CapabilitySpec> listCapabilities(String sessionId) {
        return pluginManager.registryFor(sessionId).specs();
    }
}
