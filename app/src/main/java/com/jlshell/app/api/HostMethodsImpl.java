package com.jlshell.app.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.api.server.dispatch.HostMethods;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.ConnectionProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 内置 host method 实现：直接调 core 服务。 */
public class HostMethodsImpl implements HostMethods {
    private static final Logger log = LoggerFactory.getLogger(HostMethodsImpl.class);
    private final ConnectionProfileService profileService;
    private final SessionManager sessionManager;
    private final Executor executor;
    private final String token;

    public HostMethodsImpl(ConnectionProfileService profileService, SessionManager sessionManager,
                           Executor executor, String token) {
        this.profileService = profileService;
        this.sessionManager = sessionManager;
        this.executor = executor;
        this.token = token;
    }

    @Override
    public CompletableFuture<JsonElement> sessionConnect(JsonElement params) {
        String connectionId = str(params, "connectionId");
        if (connectionId == null) return fail("missing param: connectionId");
        return CompletableFuture.supplyAsync(() -> {
            ConnectionRequest req = profileService.toConnectionRequest(connectionId);
            return sessionManager.openSession(req).thenApply(s -> {
                JsonObject o = new JsonObject();
                o.addProperty("sessionId", s.sessionId().toString());
                return (JsonElement) o;
            });
        }, executor).thenCompose(x -> x);
    }

    @Override
    public CompletableFuture<JsonElement> sessionDisconnect(JsonElement params) {
        String sid = str(params, "sessionId");
        if (sid == null) return fail("missing param: sessionId");
        return sessionManager.closeSession(toSessionId(sid)).thenApply(v -> JsonNull.INSTANCE);
    }

    @Override
    public CompletableFuture<JsonElement> sessionList(JsonElement params) {
        return CompletableFuture.supplyAsync(() -> {
            JsonArray arr = new JsonArray();
            for (SessionDescriptor d : sessionManager.listSessions()) {
                JsonObject o = new JsonObject();
                o.addProperty("sessionId", d.sessionId().toString());
                o.addProperty("displayName", d.displayName());
                o.addProperty("host", d.target().host());
                o.addProperty("user", d.target().username());
                o.addProperty("state", d.state().name());
                arr.add(o);
            }
            return arr;
        }, executor);
    }

    @Override
    public CompletableFuture<JsonElement> sessionInfo(JsonElement params) {
        String sid = str(params, "sessionId");
        if (sid == null) return fail("missing param: sessionId");
        Optional<SshSession> found = sessionManager.getSession(toSessionId(sid));
        if (found.isEmpty()) return fail("session not found: " + sid);
        SshSession s = found.get();
        JsonObject o = new JsonObject();
        o.addProperty("sessionId", s.sessionId().toString());
        o.addProperty("displayName", s.displayName());
        o.addProperty("host", s.target().host());
        o.addProperty("port", s.target().port());
        o.addProperty("user", s.target().username());
        return CompletableFuture.completedFuture(o);
    }

    @Override
    public CompletableFuture<JsonElement> commandRun(JsonElement params) {
        String sid = str(params, "sessionId");
        String command = str(params, "command");
        if (sid == null || command == null) return fail("missing param: sessionId or command");
        int timeoutSec = params.isJsonObject() && params.getAsJsonObject().has("timeoutSec")
                ? params.getAsJsonObject().get("timeoutSec").getAsInt() : 30;
        Optional<SshSession> found = sessionManager.getSession(toSessionId(sid));
        if (found.isEmpty()) return fail("session not found: " + sid);
        CommandRequest req = new CommandRequest(command, Duration.ofSeconds(timeoutSec), false, null);
        return found.get().execute(req).thenApply(r -> {
            JsonObject o = new JsonObject();
            o.addProperty("stdout", r.stdout());
            o.addProperty("stderr", r.stderr());
            o.addProperty("exitCode", r.exitCode() == null ? -1 : r.exitCode());
            return (JsonElement) o;
        });
    }

    @Override
    public CompletableFuture<JsonElement> apiToken(JsonElement params) {
        return CompletableFuture.completedFuture(new JsonPrimitive(token));
    }

    @Override
    public CompletableFuture<JsonElement> apiMethods(JsonElement params) {
        JsonArray arr = new JsonArray();
        for (String m : List.of("session.connect", "session.disconnect", "session.list",
                "session.info", "command.run", "capability.invoke", "capability.list",
                "api.token", "api.methods")) {
            arr.add(new JsonPrimitive(m));
        }
        return CompletableFuture.completedFuture(arr);
    }

    private static String str(JsonElement e, String key) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static SessionId toSessionId(String s) { return new SessionId(UUID.fromString(s)); }

    private static CompletableFuture<JsonElement> fail(String msg) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(msg));
    }
}