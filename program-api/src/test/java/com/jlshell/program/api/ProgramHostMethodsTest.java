package com.jlshell.program.api;

import com.google.gson.JsonObject;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.ShellChannel;
import com.jlshell.core.session.SshSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgramHostMethodsTest {

    @Mock SessionManager sessionManager;

    @Test
    void sessionConnectReturnsSessionId() throws Exception {
        ConnectionRequest req = new ConnectionRequest("n",
                new ConnectionTarget("h", 22, "u", Duration.ofSeconds(10), Duration.ofSeconds(30)),
                com.jlshell.core.model.AuthenticationMethod.PASSWORD,
                com.jlshell.core.security.CredentialPayload.forPassword("p".toCharArray()),
                com.jlshell.core.model.HostKeyVerificationMode.STRICT);
        SessionId sid = SessionId.randomId();
        SshSession sshSession = new FakeSshSession(sid);
        when(sessionManager.openSession(req)).thenReturn(CompletableFuture.completedFuture(sshSession));

        ProgramHostMethods host = new ProgramHostMethods(id -> req, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject();
        params.addProperty("connectionId", "c1");
        JsonObject out = host.sessionConnect(params).get().getAsJsonObject();
        assertThat(out.get("sessionId").getAsString()).isEqualTo(sid.toString());
    }

    @Test
    void commandRunExecutesViaSession() throws Exception {
        SessionId sid = SessionId.randomId();
        SshSession sshSession = new FakeSshSession(sid);
        when(sessionManager.getSession(sid)).thenReturn(Optional.of(sshSession));

        ProgramHostMethods host = new ProgramHostMethods(id -> null, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sid.toString());
        params.addProperty("command", "ls");
        JsonObject out = host.commandRun(params).get().getAsJsonObject();
        assertThat(out.get("stdout").getAsString()).isEqualTo("out");
        assertThat(out.get("exitCode").getAsInt()).isEqualTo(0);
    }

    private record FakeSshSession(SessionId sessionId) implements SshSession {
        @Override public String displayName() { return "fake"; }
        @Override public ConnectionTarget target() {
            return new ConnectionTarget("h", 22, "u", Duration.ofSeconds(10), Duration.ofSeconds(30));
        }
        @Override public SessionState state() { return SessionState.CONNECTED; }
        @Override public Instant connectedAt() { return Instant.EPOCH; }
        @Override public CompletableFuture<CommandResult> execute(CommandRequest request) {
            return CompletableFuture.completedFuture(new CommandResult(request.command(), 0, "out", "err", Duration.ZERO));
        }
        @Override public CompletableFuture<ShellChannel> openShell(ShellRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
