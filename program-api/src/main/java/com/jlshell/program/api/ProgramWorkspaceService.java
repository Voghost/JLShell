package com.jlshell.program.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Application-wide session orchestration. All targets use stable IDs, independent of UI focus. */
public interface ProgramWorkspaceService {
    enum Presentation { TAB, BACKGROUND }
    enum Reuse { NEVER, IF_CONNECTED }
    enum Ready { CONNECTED, TERMINAL, SHELL }
    enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    record ConnectionQuery(String text, String projectId, String folderId) {}
    record Connection(String connectionId, String name, String host, int port, String username,
                      String projectId, String folderId, String type) {}
    record OpenRequest(String connectionId, Presentation presentation, Reuse reuse, boolean focus) {
        public OpenRequest {
            java.util.Objects.requireNonNull(connectionId);
            java.util.Objects.requireNonNull(presentation);
            java.util.Objects.requireNonNull(reuse);
        }
    }
    record Session(String sessionId, String connectionId, String name, String host, int port,
                   String username, String state, Presentation presentation, boolean active,
                   List<String> terminalIds, List<Ready> ready) {}
    record Terminal(String sessionId, String terminalId, boolean shellReady,
                    boolean commandTracking, boolean busy, int queuedOperations) {}
    record SelectionRequest(String title, String description, boolean multiple, List<String> candidateIds) {
        public SelectionRequest { candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds); }
    }
    record Selection(boolean cancelled, List<String> sessionIds) {
        public Selection { sessionIds = List.copyOf(sessionIds); }
    }
    record Role(String key, String label, boolean required) {}
    record RoleRequest(String title, String description, List<Role> roles, List<String> candidateIds) {
        public RoleRequest { roles=List.copyOf(roles); candidateIds=candidateIds==null ? List.of() : List.copyOf(candidateIds); }
    }
    record RoleSelection(boolean cancelled, Map<String,String> sessions) {
        public RoleSelection { sessions=Map.copyOf(sessions); }
    }
    record Event(long sequence, String time, String type, String sessionId, String relatedId, String message) {}
    record Events(List<Event> events, long nextSequence, boolean truncated) {}
    record Input(String sessionId, String terminalId, String jobId, String stepId, String mode,
                 List<String> commands, int intervalMs, boolean awaitCommand, int timeoutSeconds) {
        public Input { commands=List.copyOf(commands); }
    }
    record TerminalEvent(long sequence,String time,String kind,String text,Integer exitCode) {}
    record TerminalEvents(List<TerminalEvent> events,long nextSequence,boolean truncated) {}
    record Operation(String operationId, String sessionId, String terminalId, String jobId,
                     String stepId, String kind, State state, double progress, String message,
                     String createdAt, String finishedAt) {}
    record Step(String stepId, String title, String sessionId, String terminalId, State state,
                double progress, String message) {}
    record Job(String jobId, String title, State state, List<Step> steps, String createdAt) {}
    @FunctionalInterface interface Subscription extends AutoCloseable { @Override void close(); }

    CompletableFuture<List<Connection>> connections(ConnectionQuery query);
    CompletableFuture<Session> open(OpenRequest request);
    CompletableFuture<List<Session>> sessions();
    CompletableFuture<Session> get(String sessionId);
    CompletableFuture<Session> awaitReady(String sessionId, Ready ready, int timeoutSeconds);
    CompletableFuture<Selection> choose(SelectionRequest request);
    CompletableFuture<RoleSelection> chooseRoles(RoleRequest request);
    CompletableFuture<Void> reveal(String sessionId, String terminalId, boolean focus);
    CompletableFuture<Void> closeSession(String sessionId);
    CompletableFuture<Session> reconnect(String sessionId, boolean focus);
    List<Terminal> terminals(String sessionId);
    ProgramTerminalService terminal(String sessionId, String terminalId);
    ProgramFileService files(String sessionId);
    Subscription subscribe(Consumer<Event> listener);
    Events events(long afterSequence, int limit);
    CompletableFuture<Operation> enqueue(Input input);
    Operation operation(String operationId);
    CompletableFuture<Operation> awaitOperation(String operationId,int timeoutSeconds);
    TerminalEvents terminalEvents(String sessionId,String terminalId,long afterSequence,int limit);
    boolean cancelOperation(String operationId);
    Job createJob(String title, List<Step> steps);
    Job job(String jobId);
    List<Job> jobs();
    Job updateStep(String jobId, String stepId, State state, double progress, String message);
    Job cancelJob(String jobId);

    static ProgramWorkspaceService unavailable() { return UnavailableProgramWorkspaceService.INSTANCE; }
}
