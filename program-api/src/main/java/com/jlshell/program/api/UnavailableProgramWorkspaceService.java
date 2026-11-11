package com.jlshell.program.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class UnavailableProgramWorkspaceService implements ProgramWorkspaceService {
    static final ProgramWorkspaceService INSTANCE=new UnavailableProgramWorkspaceService();
    private UnsupportedOperationException error() { return new UnsupportedOperationException("Program workspace service unavailable"); }
    private <T> CompletableFuture<T> failed() { return CompletableFuture.failedFuture(error()); }
    public CompletableFuture<List<Connection>> connections(ConnectionQuery query) { return failed(); }
    public CompletableFuture<Session> open(OpenRequest request) { return failed(); }
    public CompletableFuture<List<Session>> sessions() { return failed(); }
    public CompletableFuture<Session> get(String id) { return failed(); }
    public CompletableFuture<Session> awaitReady(String id,Ready ready,int timeout) { return failed(); }
    public CompletableFuture<Selection> choose(SelectionRequest request) { return failed(); }
    public CompletableFuture<RoleSelection> chooseRoles(RoleRequest request) { return failed(); }
    public CompletableFuture<Void> reveal(String id,String terminal,boolean focus) { return failed(); }
    public CompletableFuture<Void> closeSession(String id) { return failed(); }
    public CompletableFuture<Session> reconnect(String id,boolean focus) { return failed(); }
    public ProgramFileService files(String id) { throw error(); }
    public ProgramTerminalService terminal(String sessionId,String terminalId) { throw error(); }
    public List<Terminal> terminals(String id) { return List.of(); }
    public Subscription subscribe(Consumer<Event> listener) { return () -> {}; }
    public Events events(long after,int limit) { return new Events(List.of(),after,false); }
    public CompletableFuture<Operation> enqueue(Input input) { return failed(); }
    public CompletableFuture<Operation> awaitOperation(String id,int timeout) { return failed(); }
    public TerminalEvents terminalEvents(String session,String terminal,long after,int limit) { throw error(); }
    public Operation operation(String id) { throw error(); }
    public boolean cancelOperation(String id) { throw error(); }
    public Job createJob(String title,List<Step> steps) { throw error(); }
    public Job job(String id) { throw error(); }
    public List<Job> jobs() { return List.of(); }
    public Job updateStep(String job,String step,State state,double progress,String message) { throw error(); }
    public Job cancelJob(String id) { throw error(); }
}
