package com.jlshell.plugin.api.host;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Visible terminal access. Every operation is bound to one explicit session. */
public interface TerminalAccess {
    record Snapshot(String sessionId, String screen, String history, String selection,
                    String directory, int columns, int rows, long sequence, boolean truncated) {}
    enum EventKind { OUTPUT, INPUT, COMMAND_STARTED, COMMAND_FINISHED, CLOSED }
    /** Input is raw terminal input, not a reconstructed shell command. */
    record Event(long sequence, Instant time, EventKind kind, String text, Integer exitCode) {}
    enum Key { ENTER, INTERRUPT, TAB, ESCAPE, UP, DOWN, LEFT, RIGHT }
    /** True after the shell has emitted a recognized command-start marker. */
    default boolean commandTrackingAvailable() { return false; }
    String sessionId();
    CompletableFuture<Snapshot> snapshot(int historyLines, int maxCharacters);
    CompletableFuture<Void> paste(String text);
    CompletableFuture<Void> sendKey(Key key);
    default CompletableFuture<Void> submit(String command) {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Use paste for multiline input"));
        return paste(command).thenCompose(ignored -> sendKey(Key.ENTER));
    }
    /** Completes when input has been delivered. Cancellation stops remaining submissions. */
    CompletableFuture<Integer> submitBatch(List<String> commands, Duration interval, Consumer<Integer> progress);
    /** Live events are delivered in sequence on a background thread. Subscribers must not block.
     * Slow consumers can lose events. Sequence gaps indicate loss, and snapshot() resynchronizes state.
     * Raw input can include secrets. OUTPUT chunks can contain partial escape sequences. */
    Registration subscribe(Consumer<Event> listener);
}
