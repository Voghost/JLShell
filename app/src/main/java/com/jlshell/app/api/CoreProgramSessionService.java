package com.jlshell.app.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.program.api.ProgramCommandResult;
import com.jlshell.program.api.ProgramSession;
import com.jlshell.program.api.ProgramSessionService;

/** 将 core 会话模型适配为 Program API 的稳定 SPI 契约。 */
public final class CoreProgramSessionService implements ProgramSessionService {
    private final Function<String, ConnectionRequest> connectionRequestResolver;
    private final SessionManager sessionManager;
    private final Executor executor;

    public CoreProgramSessionService(Function<String, ConnectionRequest> connectionRequestResolver,
                                     SessionManager sessionManager,
                                     Executor executor) {
        this.connectionRequestResolver = connectionRequestResolver;
        this.sessionManager = sessionManager;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<ProgramSession> connect(String connectionId) {
        return CompletableFuture.supplyAsync(() -> connectionRequestResolver.apply(connectionId), executor)
                .thenCompose(sessionManager::openSession)
                .thenApply(CoreProgramSessionService::toProgramSession);
    }

    @Override
    public CompletableFuture<Void> disconnect(String sessionId) {
        return sessionManager.closeSession(toSessionId(sessionId));
    }

    @Override
    public List<ProgramSession> list() {
        return sessionManager.listSessions().stream().map(CoreProgramSessionService::toProgramSession).toList();
    }

    @Override
    public Optional<ProgramSession> find(String sessionId) {
        return sessionManager.getSession(toSessionId(sessionId)).map(CoreProgramSessionService::toProgramSession);
    }

    @Override
    public CompletableFuture<ProgramCommandResult> run(String sessionId, String command, Duration timeout) {
        Optional<SshSession> session = sessionManager.getSession(toSessionId(sessionId));
        if (session.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("session not found: " + sessionId));
        }
        CommandRequest request = new CommandRequest(command, timeout, false, null);
        return session.get().execute(request).thenApply(result ->
                new ProgramCommandResult(result.stdout(), result.stderr(), result.exitCode()));
    }

    private static ProgramSession toProgramSession(SessionDescriptor descriptor) {
        return new ProgramSession(
                descriptor.sessionId().toString(),
                descriptor.displayName(),
                descriptor.target().host(),
                descriptor.target().port(),
                descriptor.target().username(),
                descriptor.state().name());
    }

    private static ProgramSession toProgramSession(SshSession session) {
        return new ProgramSession(
                session.sessionId().toString(),
                session.displayName(),
                session.target().host(),
                session.target().port(),
                session.target().username(),
                session.state().name());
    }

    private static SessionId toSessionId(String value) {
        return new SessionId(UUID.fromString(value));
    }
}
