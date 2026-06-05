package com.jlshell.plugin.api.capability;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Interactive command executor for SSH sessions that require stdin/stdout interaction.
 * Unlike {@link CommandExecutor} which runs one-shot commands, this interface
 * supports multi-step interactive sessions where output is read and input is written
 * in sequence.
 */
public interface InteractiveCommandExecutor {

    /**
     * Start an interactive command session.
     *
     * @param command the command to execute on the remote host
     * @return a future that completes with an {@link InteractiveSession} once the
     *         command has started and initial output is available
     */
    CompletableFuture<InteractiveSession> start(String command);
}