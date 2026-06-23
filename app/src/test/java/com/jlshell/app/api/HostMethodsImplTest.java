package com.jlshell.app.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.ConnectionProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostMethodsImplTest {

    @Mock ConnectionProfileService profileService;
    @Mock SessionManager sessionManager;
    @Mock SshSession sshSession;

    @Test
    void sessionConnectReturnsSessionId() throws Exception {
        ConnectionRequest req = new ConnectionRequest("n",
                new ConnectionTarget("h", 22, "u", Duration.ofSeconds(10), Duration.ofSeconds(30)),
                com.jlshell.core.model.AuthenticationMethod.PASSWORD,
                com.jlshell.core.security.CredentialPayload.forPassword("p".toCharArray()),
                com.jlshell.core.model.HostKeyVerificationMode.STRICT);
        when(profileService.toConnectionRequest("c1")).thenReturn(req);
        SessionId sid = SessionId.randomId();
        when(sshSession.sessionId()).thenReturn(sid);
        when(sessionManager.openSession(req)).thenReturn(CompletableFuture.completedFuture(sshSession));

        HostMethodsImpl host = new HostMethodsImpl(profileService, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject(); params.addProperty("connectionId", "c1");
        JsonObject out = host.sessionConnect(params).get().getAsJsonObject();
        assertThat(out.get("sessionId").getAsString()).isEqualTo(sid.toString());
    }

    @Test
    void commandRunExecutesViaSession() throws Exception {
        SessionId sid = SessionId.randomId();
        when(sshSession.execute(any(CommandRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new CommandResult("ls", 0, "out", "err", Duration.ZERO)));
        when(sessionManager.getSession(sid)).thenReturn(Optional.of(sshSession));

        HostMethodsImpl host = new HostMethodsImpl(profileService, sessionManager, Runnable::run, "tok");
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sid.toString());
        params.addProperty("command", "ls");
        JsonObject out = host.commandRun(params).get().getAsJsonObject();
        assertThat(out.get("stdout").getAsString()).isEqualTo("out");
        assertThat(out.get("exitCode").getAsInt()).isEqualTo(0);
    }
}