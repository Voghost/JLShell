package com.jlshell.program.plugin.loader;

import com.jlshell.program.api.ProgramWorkspaceService;
import com.jlshell.program.api.ProgramFileService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Program plugin ownership for cross-session subscriptions and agent jobs. */
final class ScopedProgramWorkspaceService implements ProgramWorkspaceService, AutoCloseable {
    private final ProgramWorkspaceService delegate;
    private final Set<Subscription> subscriptions=ConcurrentHashMap.newKeySet();
    private final Set<String> operations=ConcurrentHashMap.newKeySet(), jobs=ConcurrentHashMap.newKeySet(), processes=ConcurrentHashMap.newKeySet();
    private final Subscription tracking;
    private volatile boolean closed;
    ScopedProgramWorkspaceService(ProgramWorkspaceService delegate) {
        this.delegate=delegate;
        tracking=delegate.subscribe(event -> {
            if(event.type().equals("OPERATION_SUCCEEDED") || event.type().equals("OPERATION_FAILED") || event.type().equals("OPERATION_CANCELLED")) operations.remove(event.relatedId());
            if(event.type().startsWith("JOB_") && jobs.contains(event.relatedId())) {
                try { if(delegate.job(event.relatedId()).steps().stream().allMatch(step -> finished(step.state()))) jobs.remove(event.relatedId()); }
                catch(IllegalArgumentException removed) { jobs.remove(event.relatedId()); }
            }
        });
    }
    private void check() { if(closed) throw new IllegalStateException("Program plugin context disposed"); }
    private static boolean finished(State state) { return state==State.SUCCEEDED || state==State.CANCELLED || state==State.FAILED || state==State.INTERRUPTED; }
    private synchronized Operation ownOperation(Operation operation) {
        if(closed) delegate.cancelOperation(operation.operationId());
        else {
            operations.add(operation.operationId());
            if(finished(delegate.operation(operation.operationId()).state())) operations.remove(operation.operationId());
        }
        return operation;
    }
    public CompletableFuture<Operation> enqueue(Input input) { check(); return delegate.enqueue(input).thenApply(this::ownOperation); }
    public synchronized Job createJob(String title,List<Step> steps) {
        check(); Job job=delegate.createJob(title,steps); jobs.add(job.jobId()); return job;
    }
    public Subscription subscribe(Consumer<Event> listener) {
        return ownSubscription(() -> delegate.subscribe(event -> { if (!closed) listener.accept(event); }));
    }
    public com.jlshell.program.api.ProgramTerminalService terminal(String sessionId, String terminalId) {
        check();
        var raw = delegate.terminal(sessionId, terminalId);
        return new com.jlshell.program.api.ProgramTerminalService() {
            public String sessionId() { return raw.sessionId(); }
            public String terminalId() { return raw.terminalId(); }
            public CompletableFuture<Snapshot> snapshot(int lines, int characters) {
                check(); return raw.snapshot(lines, characters);
            }
            public TerminalEvents events(long after, int limit) { check(); return raw.events(after, limit); }
            public Subscription subscribe(Consumer<TerminalEvent> listener) {
                return ownSubscription(() -> raw.subscribe(event -> { if (!closed) listener.accept(event); }));
            }
        };
    }
    private synchronized Subscription ownSubscription(java.util.function.Supplier<Subscription> factory) {
        check();
        var raw = factory.get();
        Subscription wrapper = new Subscription() {
            private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            public void close() {
                if (released.compareAndSet(false, true)) { raw.close(); subscriptions.remove(this); }
            }
        };
        subscriptions.add(wrapper);
        return wrapper;
    }
    public com.jlshell.program.api.ProgramProcessService processes() {
        check(); var raw = delegate.processes();
        return new com.jlshell.program.api.ProgramProcessService() {
            public CompletableFuture<Process> start(Request request) {
                check();
                return raw.start(request).thenApply(process -> {
                    synchronized (ScopedProgramWorkspaceService.this) {
                        if (closed) raw.cancel(process.processId());
                        else {
                            processes.add(process.processId());
                            raw.await(process.processId(),3600).whenComplete((done,error) -> processes.remove(process.processId()));
                        }
                    }
                    return process;
                });
            }
            public Process get(String id) { check(); return raw.get(id); }
            public CompletableFuture<Process> await(String id,int timeout) { check(); return raw.await(id,timeout); }
            public Events events(String id,long after,int limit) { check(); return raw.events(id,after,limit); }
            public Subscription subscribe(String id,Consumer<Output> listener) { return ownSubscription(() -> raw.subscribe(id,event -> { if (!closed) listener.accept(event); })); }
            public boolean cancel(String id) { check(); return raw.cancel(id); }
        };
    }
    public CompletableFuture<AgentContext> context(ContextRequest request) { check(); return delegate.context(request); }
    public CompletableFuture<Checkpoint> checkpoint(String id) { check(); return delegate.checkpoint(id); }
    public CompletableFuture<Checkpoint> checkpoint(String id,long expected,String json) { check(); return delegate.checkpoint(id,expected,json); }
    public synchronized Job resumeJob(String id,List<ResumeTarget> targets) { check(); Job job=delegate.resumeJob(id,targets); jobs.add(id); return job; }
    public ProgramFileService files(String sessionId) {
        check(); var raw=delegate.files(sessionId);
        return new ProgramFileService() {
            public CompletableFuture<Content> readVersioned(String path) { check(); return raw.readVersioned(path); }
            public CompletableFuture<WriteResult> write(WriteRequest request) { check(); return raw.write(request); }
            public CompletableFuture<WriteResult> applyPatch(PatchRequest request) { check(); return raw.applyPatch(request); }
            public CompletableFuture<List<File>> list(String path) { check(); return raw.list(path); }
            public CompletableFuture<File> stat(String path) { check(); return raw.stat(path); }
            public CompletableFuture<byte[]> readRange(String path,long offset,int length) { check(); return raw.readRange(path,offset,length); }
            public CompletableFuture<Void> rename(String source,String target) { check(); return raw.rename(source,target); }
            public CompletableFuture<Void> mkdir(String path,boolean recursive) { check(); return raw.mkdir(path,recursive); }
            public CompletableFuture<Operation> upload(String local,String remote,String job,String step) { check(); return raw.upload(local,remote,job,step).thenApply(ScopedProgramWorkspaceService.this::ownOperation); }
            public CompletableFuture<Operation> download(String remote,String local,String job,String step) { check(); return raw.download(remote,local,job,step).thenApply(ScopedProgramWorkspaceService.this::ownOperation); }
            public CompletableFuture<Void> navigate(String directory,List<String> selected) { check(); return raw.navigate(directory,selected); }
            public CompletableFuture<Void> preview(String path) { check(); return raw.preview(path); }
        };
    }
    public CompletableFuture<List<Connection>> connections(ConnectionQuery query) { check(); return delegate.connections(query); }
    public CompletableFuture<Session> open(OpenRequest request) { check(); return delegate.open(request); }
    public CompletableFuture<List<Session>> sessions() { check(); return delegate.sessions(); }
    public CompletableFuture<Session> get(String sessionId) { check(); return delegate.get(sessionId); }
    public CompletableFuture<Session> awaitReady(String sessionId, Ready ready, int timeoutSeconds) { check(); return delegate.awaitReady(sessionId,ready,timeoutSeconds); }
    public CompletableFuture<Selection> choose(SelectionRequest request) { check(); return delegate.choose(request); }
    public CompletableFuture<RoleSelection> chooseRoles(RoleRequest request) { check(); return delegate.chooseRoles(request); }
    public CompletableFuture<Void> reveal(String sessionId, String terminalId, boolean focus) { check(); return delegate.reveal(sessionId,terminalId,focus); }
    public CompletableFuture<Void> closeSession(String sessionId) { check(); return delegate.closeSession(sessionId); }
    public CompletableFuture<Session> reconnect(String sessionId, boolean focus) { check(); return delegate.reconnect(sessionId,focus); }
    public List<Terminal> terminals(String sessionId) { check(); return delegate.terminals(sessionId); }
    public Events events(long afterSequence, int limit) { check(); return delegate.events(afterSequence,limit); }
    public Operation operation(String operationId) { check(); return delegate.operation(operationId); }
    public CompletableFuture<Operation> awaitOperation(String operationId,int timeoutSeconds) { check(); return delegate.awaitOperation(operationId,timeoutSeconds); }
    public TerminalEvents terminalEvents(String sessionId,String terminalId,long afterSequence,int limit) { check(); return delegate.terminalEvents(sessionId,terminalId,afterSequence,limit); }
    public boolean cancelOperation(String operationId) { check(); return delegate.cancelOperation(operationId); }
    public Job job(String jobId) { check(); return delegate.job(jobId); }
    public List<Job> jobs() { check(); return delegate.jobs(); }
    public Job updateStep(String jobId, String stepId, State state, double progress, String message) { check(); return delegate.updateStep(jobId,stepId,state,progress,message); }
    public Job cancelJob(String jobId) { check(); return delegate.cancelJob(jobId); }
    public synchronized void close() {
        if(closed) return;
        closed=true;
        tracking.close(); subscriptions.forEach(Subscription::close); subscriptions.clear();
        for(String id:jobs) try { delegate.cancelJob(id); } catch(IllegalArgumentException removed) { }
        for(String id:operations) try { delegate.cancelOperation(id); } catch(IllegalArgumentException removed) { }
        for (String id : processes) try { delegate.processes().cancel(id); } catch (IllegalArgumentException removed) { }
        jobs.clear(); operations.clear(); processes.clear();
    }
}
