package com.jlshell.program.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Program API 可用的会话操作，不泄漏底层 SSH/core 模型。 */
public interface ProgramSessionService {

    CompletableFuture<ProgramSession> connect(String connectionId);

    CompletableFuture<Void> disconnect(String sessionId);

    List<ProgramSession> list();

    Optional<ProgramSession> find(String sessionId);

    CompletableFuture<ProgramCommandResult> run(String sessionId, String command, Duration timeout);
}
