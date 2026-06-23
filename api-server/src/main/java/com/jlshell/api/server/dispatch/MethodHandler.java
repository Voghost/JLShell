package com.jlshell.api.server.dispatch;

import java.util.concurrent.CompletableFuture;
import com.google.gson.JsonElement;

@FunctionalInterface
public interface MethodHandler {
    CompletableFuture<JsonElement> handle(JsonElement params) throws Exception;
}
