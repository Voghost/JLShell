package com.jlshell.program.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Independent remote exec channels with bounded output, separate from visible terminal input. */
public interface ProgramProcessService {
    record Request(String sessionId, List<String> argv, String directory, Map<String, String> environment,
                   int timeoutSeconds, int maxOutputCharacters) {
        public Request {
            argv = List.copyOf(argv);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }
    record Process(String processId, String sessionId, String state, String startedAt, String finishedAt,
                   Integer exitCode, String stdout, String stderr, boolean truncated, String error) {}
    record Summary(String processId, String sessionId, String state, String startedAt, String finishedAt, Integer exitCode) {}
    record Output(long sequence, String time, String stream, String text) {}
    record Events(List<Output> events, long nextSequence, boolean truncated) {}

    CompletableFuture<Process> start(Request request);
    Process get(String processId);
    CompletableFuture<Process> await(String processId, int timeoutSeconds);
    Events events(String processId, long afterSequence, int limit);
    ProgramWorkspaceService.Subscription subscribe(String processId, java.util.function.Consumer<Output> listener);
    boolean cancel(String processId);
}
