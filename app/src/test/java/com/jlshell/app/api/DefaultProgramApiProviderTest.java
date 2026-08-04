package com.jlshell.app.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.program.api.DefaultProgramApiRegistry;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiContext;
import com.jlshell.program.api.ProgramApiProvider;
import com.jlshell.program.api.ProgramApiRegistry;
import com.jlshell.program.api.ProgramCommandResult;
import com.jlshell.program.api.ProgramSession;
import com.jlshell.program.api.ProgramSessionService;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProgramApiProviderTest {

    @Test
    void registersBuiltInMethodsThroughSpi() {
        assertThat(ServiceLoader.load(ProgramApiProvider.class).stream()
                .map(ServiceLoader.Provider::type)
                .map(Class::getName))
                .contains(DefaultProgramApiProvider.class.getName());
    }

    @Test
    void keepsSessionConnectResponseCompatible() throws Exception {
        DefaultProgramApiRegistry registry = new DefaultProgramApiRegistry();
        new DefaultProgramApiProvider().activate(context(registry));
        JsonObject params = new JsonObject();
        params.addProperty("connectionId", "saved-connection");

        JsonObject result = registry.methods().get(ProgramApiCatalog.SESSION_CONNECT)
                .handle(params).get().getAsJsonObject();

        assertThat(result.entrySet()).hasSize(1);
        assertThat(result.get("sessionId").getAsString()).isEqualTo("session-1");
    }

    @Test
    void runsCommandsThroughProgramSessionContract() throws Exception {
        DefaultProgramApiRegistry registry = new DefaultProgramApiRegistry();
        new DefaultProgramApiProvider().activate(context(registry));
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "session-1");
        params.addProperty("command", "pwd");

        JsonObject result = registry.methods().get(ProgramApiCatalog.COMMAND_RUN)
                .handle(params).get().getAsJsonObject();

        assertThat(result.get("stdout").getAsString()).isEqualTo("/tmp");
        assertThat(result.get("exitCode").getAsInt()).isZero();
    }

    @Test
    void exposesOnlyNonSensitiveHostAccountStatus() throws Exception {
        DefaultProgramApiRegistry registry = new DefaultProgramApiRegistry();
        new DefaultProgramApiProvider().activate(context(registry));

        JsonObject result = registry.methods().get(ProgramApiCatalog.ACCOUNT_STATUS)
                .handle(new JsonObject()).get().getAsJsonObject();

        assertThat(result.get("authenticated").getAsBoolean()).isTrue();
        assertThat(result.get("username").getAsString()).isEqualTo("alice");
        assertThat(result.toString()).doesNotContain("token");
    }

    private static ProgramApiContext context(ProgramApiRegistry registry) {
        ProgramSession session = new ProgramSession("session-1", "demo", "host", 22, "user", "CONNECTED");
        ProgramSessionService sessions = new ProgramSessionService() {
            @Override public CompletableFuture<ProgramSession> connect(String connectionId) {
                return CompletableFuture.completedFuture(session);
            }
            @Override public CompletableFuture<Void> disconnect(String sessionId) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public List<ProgramSession> list() { return List.of(session); }
            @Override public Optional<ProgramSession> find(String sessionId) { return Optional.of(session); }
            @Override public CompletableFuture<ProgramCommandResult> run(String sessionId, String command, Duration timeout) {
                return CompletableFuture.completedFuture(new ProgramCommandResult("/tmp", "", 0));
            }
        };
        return new ProgramApiContext() {
            @Override public ProgramApiRegistry registry() { return registry; }
            @Override public ProgramSessionService sessions() { return sessions; }
            @Override public String apiToken() { return "token"; }
            @Override public java.util.concurrent.Executor executor() { return Runnable::run; }
            @Override public AccountSessionService accountSession() {
                return new AccountSessionService() {
                    @Override public AccountSession snapshot() {
                        return new AccountSession(true, "https://jlshell.oomn.net", "device-1",
                                "account-1", "alice", "alice@example.com", "user", "2030-01-01T00:00:00Z");
                    }
                    @Override public CompletableFuture<com.google.gson.JsonElement> request(
                            com.jlshell.program.api.AccountRequest request) {
                        return CompletableFuture.failedFuture(new UnsupportedOperationException());
                    }
                };
            }
        };
    }
}
