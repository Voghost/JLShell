package com.jlshell.plugin.api.rpc;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 能力总线：按 (sessionId, pluginId, capability) 路由调用。
 * plugin↔plugin 与外部 HTTP server 共用同一总线实例。
 */
public interface CapabilityBus {
    CompletableFuture<RpcResponse> invoke(RpcRequest request);
    List<CapabilitySpec> listCapabilities(String sessionId); // null = 仅全局
    default List<Capability> listRegisteredCapabilities(String sessionId) {
        return List.of();
    }
}
