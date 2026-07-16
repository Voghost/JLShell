package com.jlshell.program.api;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;

/** 一个由程序 API SPI 提供的 JSON-RPC 方法。 */
@FunctionalInterface
public interface ProgramApiMethod {

    CompletableFuture<JsonElement> handle(JsonElement params);
}
