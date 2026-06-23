package com.jlshell.plugin.api.rpc;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

/** 能力处理器：接收 JSON 参数与上下文，异步返回 JSON 结果。 */
@FunctionalInterface
public interface CapabilityHandler {
    CompletableFuture<JsonElement> invoke(JsonElement args, CapabilityContext ctx) throws Exception;
}
