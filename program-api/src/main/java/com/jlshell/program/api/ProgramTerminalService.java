package com.jlshell.program.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Read access to one explicit terminal instance. Submit input through workspace.enqueue. */
public interface ProgramTerminalService {
    record Snapshot(String sessionId, String terminalId, String screen, String history,
                    String selection, String directory, int columns, int rows,
                    long sequence, boolean truncated) {}

    String sessionId();
    String terminalId();
    CompletableFuture<Snapshot> snapshot(int historyLines, int maxCharacters);
    ProgramWorkspaceService.TerminalEvents events(long afterSequence, int limit);
    ProgramWorkspaceService.Subscription subscribe(Consumer<ProgramWorkspaceService.TerminalEvent> listener);
}
