package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

/**
 * 内置 host method 接口，由 app 实现。不经 CapabilityBus，直接调 core 服务。
 */
public interface HostMethods {
    CompletableFuture<JsonElement> sessionConnect(JsonElement params);
    CompletableFuture<JsonElement> sessionDisconnect(JsonElement params);
    CompletableFuture<JsonElement> sessionList(JsonElement params);
    CompletableFuture<JsonElement> sessionInfo(JsonElement params);
    CompletableFuture<JsonElement> commandRun(JsonElement params);
    CompletableFuture<JsonElement> apiToken(JsonElement params);
    CompletableFuture<JsonElement> apiMethods(JsonElement params);
}
