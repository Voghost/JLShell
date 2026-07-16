package com.jlshell.app.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiContext;
import com.jlshell.program.api.ProgramApiProvider;
import com.jlshell.program.api.ProgramCommandResult;
import com.jlshell.program.api.ProgramSession;

/** JLShell 内置 Program API 的 SPI 实现。 */
public final class DefaultProgramApiProvider implements ProgramApiProvider {

    @Override
    public void activate(ProgramApiContext context) {
        context.registry().register(ProgramApiCatalog.SESSION_CONNECT,
                params -> sessionConnect(context, params));
        context.registry().register(ProgramApiCatalog.SESSION_DISCONNECT,
                params -> sessionDisconnect(context, params));
        context.registry().register(ProgramApiCatalog.SESSION_LIST,
                params -> CompletableFuture.supplyAsync(() -> sessionList(context), context.executor()));
        context.registry().register(ProgramApiCatalog.SESSION_INFO,
                params -> sessionInfo(context, params));
        context.registry().register(ProgramApiCatalog.COMMAND_RUN,
                params -> commandRun(context, params));
        context.registry().register(ProgramApiCatalog.API_TOKEN,
                params -> CompletableFuture.completedFuture(new JsonPrimitive(context.apiToken())));
        context.registry().register(ProgramApiCatalog.API_METHODS,
                params -> CompletableFuture.completedFuture(apiMethods()));
    }

    private static CompletableFuture<JsonElement> sessionConnect(ProgramApiContext context, JsonElement params) {
        String connectionId = stringParam(params, "connectionId");
        if (connectionId == null) return failure("missing param: connectionId");
        return context.sessions().connect(connectionId).thenApply(session -> {
            JsonObject result = new JsonObject();
            result.addProperty("sessionId", session.sessionId());
            return result;
        });
    }

    private static CompletableFuture<JsonElement> sessionDisconnect(ProgramApiContext context, JsonElement params) {
        String sessionId = stringParam(params, "sessionId");
        if (sessionId == null) return failure("missing param: sessionId");
        return context.sessions().disconnect(sessionId).thenApply(ignored -> JsonNull.INSTANCE);
    }

    private static JsonElement sessionList(ProgramApiContext context) {
        JsonArray sessions = new JsonArray();
        context.sessions().list().forEach(session -> {
            JsonObject result = new JsonObject();
            result.addProperty("sessionId", session.sessionId());
            result.addProperty("displayName", session.displayName());
            result.addProperty("host", session.host());
            result.addProperty("user", session.user());
            result.addProperty("state", session.state());
            sessions.add(result);
        });
        return sessions;
    }

    private static CompletableFuture<JsonElement> sessionInfo(ProgramApiContext context, JsonElement params) {
        String sessionId = stringParam(params, "sessionId");
        if (sessionId == null) return failure("missing param: sessionId");
        Optional<ProgramSession> session = context.sessions().find(sessionId);
        return session.<CompletableFuture<JsonElement>>map(value -> CompletableFuture.completedFuture(toJson(value)))
                .orElseGet(() -> failure("session not found: " + sessionId));
    }

    private static CompletableFuture<JsonElement> commandRun(ProgramApiContext context, JsonElement params) {
        String sessionId = stringParam(params, "sessionId");
        String command = stringParam(params, "command");
        if (sessionId == null || command == null) return failure("missing param: sessionId or command");
        int timeoutSec = intParam(params, "timeoutSec", 30);
        return context.sessions().run(sessionId, command, Duration.ofSeconds(timeoutSec))
                .thenApply(DefaultProgramApiProvider::toJson);
    }

    private static JsonObject toJson(ProgramSession session) {
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", session.sessionId());
        result.addProperty("displayName", session.displayName());
        result.addProperty("host", session.host());
        result.addProperty("port", session.port());
        result.addProperty("user", session.user());
        result.addProperty("state", session.state());
        return result;
    }

    private static JsonObject toJson(ProgramCommandResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("stdout", result.stdout());
        json.addProperty("stderr", result.stderr());
        json.addProperty("exitCode", result.exitCode() == null ? -1 : result.exitCode());
        return json;
    }

    private static JsonArray apiMethods() {
        JsonArray methods = new JsonArray();
        ProgramApiCatalog.methodNames().forEach(method -> methods.add(new JsonPrimitive(method)));
        return methods;
    }

    private static String stringParam(JsonElement params, String name) {
        if (params == null || !params.isJsonObject()) return null;
        JsonObject object = params.getAsJsonObject();
        if (!object.has(name) || object.get(name).isJsonNull()) return null;
        return object.get(name).getAsString();
    }

    private static int intParam(JsonElement params, String name, int fallback) {
        if (params == null || !params.isJsonObject()) return fallback;
        JsonObject object = params.getAsJsonObject();
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : fallback;
    }

    private static CompletableFuture<JsonElement> failure(String message) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }
}
