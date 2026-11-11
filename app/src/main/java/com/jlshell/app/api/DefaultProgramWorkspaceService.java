package com.jlshell.app.api;

import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.program.api.ProgramWorkspaceService;
import com.jlshell.ui.service.ConnectionProfileService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coordinates explicit session targets. UI focus never participates in target resolution. */
public final class DefaultProgramWorkspaceService implements ProgramWorkspaceService, AutoCloseable {
    private final ConnectionProfileService connections;
    private final SessionManager sessions;
    private final Executor executor;
    private final java.util.function.Function<String,com.jlshell.plugin.api.capability.FileExplorer> fileAccess;
    private final ConcurrentMap<String,CompletableFuture<Session>> opening=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,OperationRun> operations=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Job> jobs=new ConcurrentHashMap<>();
    private static final class TerminalQueue { CompletableFuture<Void> tail=CompletableFuture.completedFuture(null); }
    private final ConcurrentMap<String,TerminalQueue> terminalQueues=new ConcurrentHashMap<>();
    private final ArrayDeque<Event> journal=new ArrayDeque<>();
    private final ArrayBlockingQueue<Event> delivery=new ArrayBlockingQueue<>(512);
    private final CopyOnWriteArrayList<Consumer<Event>> listeners=new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("workspace-events").factory());
    private final Thread dispatcher=Thread.ofVirtual().name("workspace-event-delivery").unstarted(this::dispatch);
    private Map<String,Session> previous=Map.of();
    private long sequence;
    private volatile boolean closed;
    private final com.jlshell.plugin.api.lifecycle.Registration workspaceChanges;

    public DefaultProgramWorkspaceService(ConnectionProfileService connections,SessionManager sessions,Executor executor,
            java.util.function.Function<String,com.jlshell.plugin.api.capability.FileExplorer> fileAccess) {
        this.connections=connections; this.sessions=sessions; this.executor=executor; this.fileAccess=fileAccess;
        workspaceChanges = HostExtensionRegistry.onWorkspaceChange(change -> emit(change.type(), change.sessionId(), change.relatedId(), ""));
        dispatcher.start(); monitor.scheduleWithFixedDelay(this::observe,0,100,TimeUnit.MILLISECONDS);
    }
    private void requireOpen() { if(closed) throw new IllegalStateException("Workspace service closed"); }
    public CompletableFuture<List<Connection>> connections(ConnectionQuery query) {
        requireOpen();
        return CompletableFuture.supplyAsync(() -> {
            String text=query==null || query.text()==null ? "" : query.text().toLowerCase(Locale.ROOT);
            return connections.listProfiles().stream()
                    .filter(profile -> query==null || query.projectId()==null || query.projectId().equals(profile.projectId()))
                    .filter(profile -> query==null || query.folderId()==null || query.folderId().equals(profile.folderId()))
                    .filter(profile -> (profile.displayName()+" "+profile.host()+" "+profile.description()).toLowerCase(Locale.ROOT).contains(text))
                    .map(profile -> new Connection(profile.id(),profile.displayName(),profile.host(),profile.port(),profile.username(),
                            profile.projectId(),profile.folderId(),profile.connectionType().name())).toList();
        },executor);
    }
    public CompletableFuture<Session> open(OpenRequest request) {
        requireOpen(); Objects.requireNonNull(request);
        if(request.reuse()==Reuse.NEVER) return openNew(request);
        synchronized(opening) {
            var existing=readSessions().stream().filter(session -> request.connectionId().equals(session.connectionId())
                    && session.ready().contains(Ready.CONNECTED)
                    && (request.presentation()==Presentation.BACKGROUND || session.presentation()==Presentation.TAB)).findFirst();
            if(existing.isPresent()) {
                Session session=existing.get();
                return request.focus() && session.presentation()==Presentation.TAB
                        ? reveal(session.sessionId(),null,true).thenApply(ignored -> session) : CompletableFuture.completedFuture(session);
            }
            String key=request.connectionId()+"/"+request.presentation();
            var pending=opening.get(key);
            if(pending!=null) return pending.thenCompose(session -> request.focus() && session.presentation()==Presentation.TAB
                    ? reveal(session.sessionId(),null,true).thenApply(ignored -> session) : CompletableFuture.completedFuture(session));
            var result=openNew(request); opening.put(key,result);
            result.whenComplete((value,error) -> opening.remove(key,result));
            return result;
        }
    }
    private CompletableFuture<Session> openNew(OpenRequest request) {
        try {
            return HostExtensionRegistry.workspaceUi().open(request).thenCompose(id -> {
                HostExtensionRegistry.bind(id,request.connectionId(),request.presentation());
                return get(id);
            });
        } catch(RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private List<Session> readSessions() {
        Map<String,Session> result=new LinkedHashMap<>();
        for(var descriptor:sessions.listSessions()) {
            String id=descriptor.sessionId().toString();
            var visible=HostExtensionRegistry.sessions().stream().filter(value -> value.id().equals(id)).findFirst();
            boolean connected=descriptor.state()==com.jlshell.core.model.SessionState.CONNECTED
                    && visible.map(com.jlshell.plugin.api.host.HostExtensions.Session::connected).orElse(true);
            result.put(id,describe(id,descriptor.displayName(),descriptor.target().host(),descriptor.target().port(),
                    descriptor.target().username(),connected?"CONNECTED":descriptor.state()==com.jlshell.core.model.SessionState.CONNECTED?"DISCONNECTED":descriptor.state().name(),visible.map(com.jlshell.plugin.api.host.HostExtensions.Session::active).orElse(false),connected));
        }
        for(var visible:HostExtensionRegistry.sessions()) if(!result.containsKey(visible.id())) {
            result.put(visible.id(),describe(visible.id(),visible.title(),visible.host(),0,visible.username(),
                    visible.connected()?"CONNECTED":"CLOSED",visible.active(),visible.connected()));
        }
        return List.copyOf(result.values());
    }
    private Session describe(String id,String title,String host,int port,String user,String state,boolean active,boolean connected) {
        var binding=HostExtensionRegistry.binding(id).orElse(null);
        var terminals=HostExtensionRegistry.terminalEntries(id);
        List<Ready> ready=new ArrayList<>();
        if(connected) ready.add(Ready.CONNECTED);
        if(connected && !terminals.isEmpty()) ready.add(Ready.TERMINAL);
        if(connected && terminals.stream().anyMatch(entry -> entry.access().shellReady())) ready.add(Ready.SHELL);
        return new Session(id,binding==null?null:binding.connectionId(),title,host,port,user,state,
                binding==null ? (terminals.isEmpty()?Presentation.BACKGROUND:Presentation.TAB) : binding.presentation(),active,
                terminals.stream().map(HostExtensionRegistry.TerminalEntry::terminalId).toList(),List.copyOf(ready));
    }
    public CompletableFuture<List<Session>> sessions() { requireOpen(); return CompletableFuture.completedFuture(readSessions()); }
    public CompletableFuture<Session> get(String id) {
        return sessions().thenApply(values -> values.stream().filter(session -> session.sessionId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Session not found: "+id)));
    }
    public CompletableFuture<Session> awaitReady(String id,Ready ready,int timeoutSeconds) {
        requireTimeout(timeoutSeconds); Objects.requireNonNull(ready);
        return CompletableFuture.supplyAsync(() -> {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while(true) {
                requireOpen(); Session session=get(id).join();
                if(session.ready().contains(ready)) return session;
                if(ready!=Ready.CONNECTED && session.presentation()==Presentation.BACKGROUND) throw new IllegalStateException("Background session has no terminal. Reveal it or open TAB presentation.");
                if(!session.ready().contains(Ready.CONNECTED)) throw new IllegalStateException("Session disconnected while waiting for "+ready);
                if(System.nanoTime()>=deadline) throw new CompletionException(new TimeoutException("Session did not reach "+ready));
                sleep(20);
            }
        },executor);
    }
    public CompletableFuture<Selection> choose(SelectionRequest request) {
        requireOpen(); return HostExtensionRegistry.workspaceUi().choose(request).thenApply(selection -> {
            if(!selection.cancelled()) selection.sessionIds().forEach(id -> get(id).join());
            return selection;
        });
    }
    public CompletableFuture<RoleSelection> chooseRoles(RoleRequest request) {
        requireOpen(); return HostExtensionRegistry.workspaceUi().chooseRoles(request).thenApply(selection -> {
            if(!selection.cancelled()) selection.sessions().values().forEach(id -> get(id).join());
            return selection;
        });
    }
    public CompletableFuture<Void> reveal(String id,String terminalId,boolean focus) {
        return get(id).thenCompose(session -> {
            if(terminalId!=null) HostExtensionRegistry.resolveTerminal(id,terminalId);
            return HostExtensionRegistry.workspaceUi().reveal(id,terminalId,focus);
        });
    }
    public CompletableFuture<Void> closeSession(String id) {
        return get(id).thenCompose(session -> HostExtensionRegistry.workspaceUi().closeSession(id)).thenRun(() -> {
            operations.values().stream().filter(run -> run.info.sessionId().equals(id)).forEach(run -> cancelOperation(run.info.operationId()));
            HostExtensionRegistry.unbind(id);
        });
    }
    public CompletableFuture<Session> reconnect(String id,boolean focus) {
        return get(id).thenCompose(session -> {
            if(session.connectionId()==null) return CompletableFuture.failedFuture(new IllegalStateException("Session has no saved connection"));
            return closeSession(id).thenCompose(ignored -> open(new OpenRequest(session.connectionId(),session.presentation(),Reuse.NEVER,focus)))
                    .thenApply(replacement -> { emit("RECONNECTED",replacement.sessionId(),id,""); return replacement; });
        });
    }
    public List<Terminal> terminals(String id) {
        requireOpen();
        return HostExtensionRegistry.terminalEntries(id).stream().map(entry -> new Terminal(id,entry.terminalId(),entry.access().shellReady(),
                entry.access().commandTrackingAvailable(),entry.access().commandRunning() || operations.values().stream().anyMatch(run -> entry.terminalId().equals(run.info.terminalId()) && run.info.state()==State.RUNNING),
                (int)operations.values().stream().filter(run -> entry.terminalId().equals(run.info.terminalId()) && run.info.state()==State.QUEUED).count())).toList();
    }
    public com.jlshell.program.api.ProgramTerminalService terminal(String sessionId, String terminalId) {
        requireOpen();
        TerminalAccess target = HostExtensionRegistry.resolveTerminal(sessionId, terminalId);
        String resolvedId = target.terminalId();
        return new com.jlshell.program.api.ProgramTerminalService() {
            private TerminalAccess current() {
                requireOpen();
                return HostExtensionRegistry.resolveTerminal(sessionId, resolvedId);
            }
            public String sessionId() { return sessionId; }
            public String terminalId() { return resolvedId; }
            public CompletableFuture<Snapshot> snapshot(int lines, int characters) {
                return current().snapshot(lines, characters).thenApply(value -> new Snapshot(
                        sessionId, resolvedId, value.screen(), value.history(), value.selection(),
                        value.directory(), value.columns(), value.rows(), value.sequence(), value.truncated()));
            }
            public TerminalEvents events(long after, int limit) {
                return terminalEvents(sessionId, resolvedId, after, limit);
            }
            public Subscription subscribe(Consumer<TerminalEvent> listener) {
                var registration = current().subscribe(event -> listener.accept(terminalEvent(event)));
                return registration::close;
            }
        };
    }
    public com.jlshell.program.api.ProgramFileService files(String sessionId) {
        requireOpen();
        return new com.jlshell.program.api.ProgramFileService() {
            private com.jlshell.plugin.api.capability.FileExplorer delegate() { return fileAccess.apply(sessionId); }
            private File describeFile(com.jlshell.plugin.api.model.RemoteFile file) {
                return new File(file.name(),file.path(),file.size(),file.isDirectory(),file.permissions(),file.lastModified()==null?null:file.lastModified().toString());
            }
            public CompletableFuture<List<File>> list(String path) { return delegate().listDirectory(path).thenApply(values -> values.stream().map(this::describeFile).toList()); }
            public CompletableFuture<File> stat(String path) { return delegate().stat(path).thenApply(this::describeFile); }
            public CompletableFuture<byte[]> readRange(String path,long offset,int length) { return delegate().readRange(path,offset,length); }
            public CompletableFuture<Void> rename(String source,String target) { return delegate().rename(source,target); }
            public CompletableFuture<Void> mkdir(String path,boolean recursive) { return delegate().createDirectory(path,recursive); }
            public CompletableFuture<Operation> upload(String local,String remote,String job,String step) { return transfer(sessionId,local,remote,true,job,step); }
            public CompletableFuture<Operation> download(String remote,String local,String job,String step) { return transfer(sessionId,local,remote,false,job,step); }
            public CompletableFuture<Void> navigate(String directory,List<String> selected) {
                return HostExtensionRegistry.workspaceUi().ensureFiles(sessionId).thenCompose(ignored -> HostExtensionRegistry.navigate(sessionId,true,directory,selected));
            }
            public CompletableFuture<Void> preview(String path) {
                return stat(path).thenCompose(file -> HostExtensionRegistry.workspaceUi().ensureFiles(sessionId).thenCompose(ignored ->
                        HostExtensionRegistry.openPreview(new com.jlshell.plugin.api.host.HostExtensions.FileRef(sessionId,file.path(),file.name(),file.directory(),file.size()),sessionId)));
            }
        };
    }
    private CompletableFuture<Operation> transfer(String sessionId,String local,String remote,boolean upload,String jobId,String stepId) {
        try {
            get(sessionId).join();
            var file=fileAccess.apply(sessionId);
            var placeholder=new Input(sessionId,null,jobId,stepId,"TRANSFER",List.of(),0,false,60);
            synchronized(operations) {
                pruneOperations(); validateStep(placeholder,null);
                if(operations.values().stream().filter(run -> !terminal(run.info.state())).count()>=128) throw new IllegalStateException("Too many active operations");
                String id=UUID.randomUUID().toString();
                var run=new OperationRun(new Operation(id,sessionId,null,jobId,stepId,upload?"UPLOAD":"DOWNLOAD",State.QUEUED,0,"",Instant.now().toString(),null));
                operations.put(id,run);
                CompletableFuture.runAsync(() -> {
                    if(run.cancelled.get()) return;
                    transition(run,State.RUNNING,0,"");
                    try {
                        java.util.function.Consumer<com.jlshell.plugin.api.capability.FileExplorer.Progress> progress=value -> {
                            long now=System.nanoTime();
                            if(now-run.lastProgress<100_000_000L && value.transferred()<value.total()) return;
                            run.lastProgress=now;
                            transition(run,State.RUNNING,value.total()>0?Math.min(1,(double)value.transferred()/value.total()):0,"Transferring file");
                        };
                        CompletableFuture<Void> future=upload?file.upload(java.nio.file.Path.of(local),remote,progress,run.cancelled::get)
                                :file.download(remote,java.nio.file.Path.of(local),progress,run.cancelled::get);
                        run.current=future;
                        if(run.cancelled.get()) future.cancel(true);
                        future.whenComplete((value,error) -> transition(run,run.cancelled.get()?State.CANCELLED:error==null?State.SUCCEEDED:State.FAILED,
                                error==null?1:run.info.progress(),error==null?"":safeError(error)));
                    } catch(RuntimeException error) { transition(run,State.FAILED,run.info.progress(),safeError(error)); }
                },executor);
                return CompletableFuture.completedFuture(run.info);
            }
        } catch(RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    public TerminalEvents terminalEvents(String session,String terminal,long after,int limit) {
        var page=HostExtensionRegistry.resolveTerminal(session,terminal).events(after,limit);
        return new TerminalEvents(page.events().stream().map(DefaultProgramWorkspaceService::terminalEvent).toList(),page.nextSequence(),page.truncated());
    }
    private static TerminalEvent terminalEvent(TerminalAccess.Event event) {
        return new TerminalEvent(event.sequence(), event.time().toString(), event.kind().name(), event.text(), event.exitCode());
    }
    public Subscription subscribe(Consumer<Event> listener) { requireOpen(); listeners.add(Objects.requireNonNull(listener)); return () -> listeners.remove(listener); }
    public synchronized Events events(long after,int limit) {
        if(after<0 || limit<1 || limit>512) throw new IllegalArgumentException("Invalid event page");
        var page=journal.stream().filter(event -> event.sequence()>after).limit(limit).toList();
        return new Events(page,page.isEmpty()?Math.max(after,sequence):page.getLast().sequence(),!journal.isEmpty() && after<journal.getFirst().sequence()-1);
    }
    public synchronized void emit(String type,String session,String related,String message) {
        if(closed) return;
        Event event=new Event(++sequence,Instant.now().toString(),type,session,related,message);
        if(journal.size()==512) journal.removeFirst(); journal.addLast(event);
        if(!delivery.offer(event)) { delivery.poll(); delivery.offer(event); }
    }
    private void dispatch() {
        try { while(!closed) {
            var event=delivery.take();
            for(var listener:listeners) try { listener.accept(event); } catch(RuntimeException error) { HostExtensionRegistry.report(error); }
        }} catch(InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    private void observe() {
        try {
            Map<String,Session> current=new LinkedHashMap<>(); readSessions().forEach(session -> current.put(session.sessionId(),session));
            for(var session:current.values()) {
                Session old=previous.get(session.sessionId());
                if(old==null) emit("OPENED",session.sessionId(),null,session.state());
                else {
                    if(old.active()!=session.active()) emit("ACTIVE_CHANGED",session.sessionId(),null,Boolean.toString(session.active()));
                    if(old.ready().contains(Ready.CONNECTED) && !session.ready().contains(Ready.CONNECTED)) emit("DISCONNECTED",session.sessionId(),null,session.state());
                }
                for(Ready stage:session.ready()) if(old==null || !old.ready().contains(stage)) emit(stage+"_READY",session.sessionId(),null,"");
            }
            for(String id:previous.keySet()) if(!current.containsKey(id)) { emit("CLOSED",id,null,""); HostExtensionRegistry.unbind(id); }
            terminalQueues.keySet().removeIf(id -> current.values().stream().noneMatch(session -> session.terminalIds().contains(id))
                    && operations.values().stream().noneMatch(run -> id.equals(run.info.terminalId()) && !terminal(run.info.state())));
            previous=current;
            for(var run:operations.values()) if(!terminal(run.info.state())) {
                if(!current.containsKey(run.info.sessionId())) cancelOperation(run.info.operationId());
                else if(System.nanoTime()>=run.deadline) {
                    transition(run,State.FAILED,run.info.progress(),"Operation timed out");
                    run.cancelled.set(true); if(run.current!=null) run.current.cancel(true);
                }
            }
        } catch(RuntimeException error) { if(!closed) HostExtensionRegistry.report(error); }
    }

    private static final class OperationRun {
        volatile Operation info;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Operation> result=new CompletableFuture<>();
        volatile CompletableFuture<?> current;
        volatile long lastProgress;
        volatile long deadline=Long.MAX_VALUE;
        OperationRun(Operation info) { this.info=info; }
    }
    public CompletableFuture<Operation> enqueue(Input input) {
        requireOpen();
        try {
            requireTimeout(input.timeoutSeconds());
            if(input.commands().isEmpty() || input.commands().size()>1000 || input.intervalMs()<0 || input.intervalMs()>60000)
                throw new IllegalArgumentException("Invalid terminal input batch");
            if(input.commands().stream().mapToLong(String::length).sum()>1_000_000) throw new IllegalArgumentException("Input batch too large");
            String mode=input.mode().toUpperCase(Locale.ROOT);
            if(!Set.of("PASTE","KEY","SUBMIT","BATCH").contains(mode)) throw new IllegalArgumentException("Unsupported terminal input mode");
            if(!mode.equals("BATCH") && input.commands().size()!=1) throw new IllegalArgumentException("This mode accepts one input");
            if((mode.equals("SUBMIT") || mode.equals("BATCH")) && input.commands().stream().anyMatch(command -> command.indexOf('\n')>=0 || command.indexOf('\r')>=0))
                throw new IllegalArgumentException("Commands must be single line");
            if (input.awaitCommand() && !mode.equals("SUBMIT") && !mode.equals("BATCH"))
                throw new IllegalArgumentException("awaitCommand requires SUBMIT or BATCH mode");
            TerminalAccess target=HostExtensionRegistry.resolveTerminal(input.sessionId(),input.terminalId());
            if(input.awaitCommand() && !target.commandTrackingAvailable()) throw new IllegalStateException("Shell command tracking is unavailable");
            synchronized(operations) {
                pruneOperations();
                if(operations.values().stream().filter(run -> !terminal(run.info.state())).count()>=128) throw new IllegalStateException("Too many queued terminal operations");
                validateStep(input,target.terminalId());
                String id=UUID.randomUUID().toString();
                OperationRun run=new OperationRun(new Operation(id,input.sessionId(),target.terminalId(),input.jobId(),input.stepId(),mode,State.QUEUED,0,"",Instant.now().toString(),null));
                run.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(input.timeoutSeconds());
                operations.put(id,run);
                var queue=terminalQueues.computeIfAbsent(target.terminalId(),key -> new TerminalQueue());
                synchronized(queue) { queue.tail=queue.tail.handle((value,error) -> null).thenRunAsync(() -> execute(run,target,input),executor); }
                return CompletableFuture.completedFuture(run.info);
            }
        } catch(RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private void execute(OperationRun run,TerminalAccess target,Input input) {
        if(terminal(run.info.state())) return;
        long deadline=run.deadline;
        try {
            checkRun(run,deadline);
            transition(run,State.RUNNING,0,"");
            for(int index=0;index<input.commands().size();index++) {
                checkRun(run,deadline);
                String command=input.commands().get(index);
                if(input.awaitCommand()) while(target.commandRunning()) { checkRun(run,deadline); sleep(10); }
                CompletableFuture<Void> commandFinished=new CompletableFuture<>();
                AtomicBoolean started=new AtomicBoolean();
                long boundary;
                try { boundary = target.events(0, 256).nextSequence(); }
                catch (UnsupportedOperationException unavailable) { boundary = 0; }
                final long afterSequence = boundary;
                try(var subscription=target.subscribe(event -> {
                    if (event.sequence() <= afterSequence) return;
                    if(event.kind()==TerminalAccess.EventKind.COMMAND_STARTED) started.set(true);
                    if(event.kind()==TerminalAccess.EventKind.COMMAND_FINISHED && started.get()) {
                        if(event.exitCode()!=null && event.exitCode()!=0) commandFinished.completeExceptionally(new IllegalStateException("Command exited with code "+event.exitCode()));
                        else commandFinished.complete(null);
                    }
                    if(event.kind()==TerminalAccess.EventKind.CLOSED) commandFinished.completeExceptionally(new IllegalStateException("Terminal closed"));
                })) {
                    CompletableFuture<Void> sent=switch(run.info.kind()) {
                        case "PASTE" -> target.paste(command);
                        case "KEY" -> target.sendKey(TerminalAccess.Key.valueOf(command));
                        default -> target.submit(command);
                    };
                    run.current=sent; awaitFuture(sent,run,deadline);
                    if(input.awaitCommand()) { run.current=commandFinished; awaitFuture(commandFinished,run,deadline); }
                }
                transition(run,State.RUNNING,(index+1.0)/input.commands().size(),"Input delivered");
                if(index+1<input.commands().size()) {
                    long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(input.intervalMs());
                    while(System.nanoTime()<until) { checkRun(run,deadline); sleep(10); }
                }
            }
            transition(run,State.SUCCEEDED,1,"");
        } catch(Throwable error) {
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            transition(run,run.cancelled.get()?State.CANCELLED:State.FAILED,run.info.progress(),safeError(error));
        } finally {
            run.current=null;

        }
    }
    private void awaitFuture(CompletableFuture<?> future,OperationRun run,long deadline) throws Exception {
        while(true) {
            checkRun(run,deadline);
            try { future.get(20,TimeUnit.MILLISECONDS); return; } catch(TimeoutException wait) { /* Check cancellation before waiting again. */ }
        }
    }
    private void checkRun(OperationRun run,long deadline) throws TimeoutException {
        requireOpen();
        if(run.cancelled.get()) throw new CancellationException();
        if (!get(run.info.sessionId()).join().ready().contains(Ready.CONNECTED))
            throw new IllegalStateException("Session disconnected");
        HostExtensionRegistry.resolveTerminal(run.info.sessionId(),run.info.terminalId());
        if(System.nanoTime()>=deadline) throw new TimeoutException("Terminal operation timed out");
    }
    private synchronized void transition(OperationRun run,State state,double progress,String message) {
        Operation old=run.info;
        if(terminal(old.state())) return;
        run.info=new Operation(old.operationId(),old.sessionId(),old.terminalId(),old.jobId(),old.stepId(),old.kind(),state,progress,message,old.createdAt(),terminal(state)?Instant.now().toString():null);
        if(old.jobId()!=null) updateStepState(old.jobId(),old.stepId(),state,progress,message);
        emit("OPERATION_"+state,old.sessionId(),old.operationId(),message);
        if(terminal(state)) run.result.complete(run.info);
    }
    public Operation operation(String id) { var run=operations.get(id); if(run==null) throw new IllegalArgumentException("Operation not found"); return run.info; }
    public CompletableFuture<Operation> awaitOperation(String id,int timeout) {
        requireTimeout(timeout);
        var run=operations.get(id);
        if(run==null) return CompletableFuture.failedFuture(new IllegalArgumentException("Operation not found"));
        return run.result.thenApply(value -> value).orTimeout(timeout,TimeUnit.SECONDS);
    }
    public boolean cancelOperation(String id) {
        var run=operations.get(id);
        if(run==null) throw new IllegalArgumentException("Operation not found");
        synchronized(this) {
            if(terminal(run.info.state())) return false;
            run.cancelled.set(true);
            var current=run.current; if(current!=null) current.cancel(true);
            transition(run,State.CANCELLED,run.info.progress(),"Cancelled. Input already delivered may still be executing.");
            return true;
        }
    }
    private void pruneOperations() {
        if(operations.size()<512) return;
        operations.values().stream().filter(run -> terminal(run.info.state())).sorted(Comparator.comparing(run -> run.info.createdAt()))
                .limit(256).forEach(run -> operations.remove(run.info.operationId(),run));
    }
    public synchronized Job createJob(String title,List<Step> steps) {
        requireOpen();
        if(title==null || title.isBlank() || steps.isEmpty() || steps.size()>100) throw new IllegalArgumentException("A job needs a title and 1..100 steps");
        var ids=new HashSet<String>();
        List<Step> initial=new ArrayList<>();
        for(Step step:steps) {
            if(step.stepId()==null || step.stepId().isBlank() || !ids.add(step.stepId())) throw new IllegalArgumentException("Step IDs must be unique");
            get(step.sessionId()).join();
            if(step.terminalId()!=null) HostExtensionRegistry.resolveTerminal(step.sessionId(),step.terminalId());
            initial.add(new Step(step.stepId(),step.title(),step.sessionId(),step.terminalId(),State.QUEUED,0,""));
        }
        if(jobs.size()>=256) {
            jobs.values().stream().filter(job -> terminal(job.state())).sorted(Comparator.comparing(Job::createdAt)).limit(128).forEach(job -> jobs.remove(job.jobId(),job));
            if(jobs.size()>=256) throw new IllegalStateException("Too many active jobs");
        }
        Job job=new Job(UUID.randomUUID().toString(),title,State.QUEUED,List.copyOf(initial),Instant.now().toString());
        jobs.put(job.jobId(),job); emit("JOB_CREATED",null,job.jobId(),title); return job;
    }
    private synchronized void validateStep(Input input,String terminalId) {
        if(input.jobId()==null) { if(input.stepId()!=null) throw new IllegalArgumentException("stepId requires jobId"); return; }
        Job job=job(input.jobId());
        if(terminal(job.state())) throw new IllegalStateException("Job is already finished");
        Step step=job.steps().stream().filter(value -> value.stepId().equals(input.stepId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Step not found"));
        if(!step.sessionId().equals(input.sessionId()) || step.terminalId()!=null && !step.terminalId().equals(terminalId)) throw new IllegalArgumentException("Operation target differs from job step");
        if(terminal(step.state()) || operations.values().stream().anyMatch(run -> input.jobId().equals(run.info.jobId()) && input.stepId().equals(run.info.stepId()) && !terminal(run.info.state())))
            throw new IllegalStateException("Step is finished or already has an operation");
    }
    public Job job(String id) { Job job=jobs.get(id); if(job==null) throw new IllegalArgumentException("Job not found"); return job; }
    public List<Job> jobs() { return jobs.values().stream().sorted(Comparator.comparing(Job::createdAt)).toList(); }
    public synchronized Job updateStep(String id,String stepId,State state,double progress,String message) {
        if (operations.values().stream().anyMatch(run -> id.equals(run.info.jobId())
                && stepId.equals(run.info.stepId()) && !terminal(run.info.state()))) {
            throw new IllegalStateException("Operation-managed steps are updated by their operation");
        }
        return updateStepState(id, stepId, state, progress, message);
    }
    private Job updateStepState(String id,String stepId,State state,double progress,String message) {
        if(!Double.isFinite(progress) || progress<0 || progress>1) throw new IllegalArgumentException("Progress must be 0..1");
        Job old=job(id);
        if(old.state()==State.CANCELLED) return old;
        boolean found=false; List<Step> steps=new ArrayList<>();
        for(Step step:old.steps()) {
            if(!step.stepId().equals(stepId)) { steps.add(step); continue; }
            found=true;
            if (step.state() == State.RUNNING && state == State.QUEUED) throw new IllegalStateException("Running step cannot return to queued");
            if(terminal(step.state()) && step.state()!=state) throw new IllegalStateException("Finished step cannot change state");
            steps.add(new Step(step.stepId(),step.title(),step.sessionId(),step.terminalId(),Objects.requireNonNull(state),progress,message));
        }
        if(!found) throw new IllegalArgumentException("Step not found");
        State aggregate=steps.stream().allMatch(step -> step.state()==State.QUEUED)?State.QUEUED
                :steps.stream().allMatch(step -> step.state()==State.SUCCEEDED)?State.SUCCEEDED
                :steps.stream().anyMatch(step -> step.state()==State.FAILED)?State.FAILED
                :steps.stream().allMatch(step -> terminal(step.state()))?State.CANCELLED:State.RUNNING;
        Job updated=new Job(id,old.title(),aggregate,List.copyOf(steps),old.createdAt()); jobs.put(id,updated);
        emit("JOB_UPDATED",null,id,aggregate.name()); return updated;
    }
    public synchronized Job cancelJob(String id) {
        Job old=job(id); if(old.state()==State.SUCCEEDED || old.state()==State.CANCELLED) return old;
        Job cancelled=new Job(id,old.title(),State.CANCELLED,old.steps().stream().map(step -> terminal(step.state())?step:
                new Step(step.stepId(),step.title(),step.sessionId(),step.terminalId(),State.CANCELLED,step.progress(),"Cancelled")).toList(),old.createdAt());
        jobs.put(id,cancelled);
        operations.values().stream().filter(run -> id.equals(run.info.jobId()) && !terminal(run.info.state())).forEach(run -> cancelOperation(run.info.operationId()));
        emit("JOB_CANCELLED",null,id,""); return cancelled;
    }
    private static boolean terminal(State state) { return state==State.SUCCEEDED || state==State.FAILED || state==State.CANCELLED; }
    private static void requireTimeout(int value) { if(value<1 || value>3600) throw new IllegalArgumentException("Timeout must be 1..3600 seconds"); }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    private static String safeError(Throwable error) {
        while((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause()!=null) error=error.getCause();
        return Objects.toString(error.getMessage(),error.getClass().getSimpleName());
    }
    @Override public void close() {
        if(closed) return;
        for(var run:operations.values()) if(!terminal(run.info.state())) cancelOperation(run.info.operationId());
        closed=true; workspaceChanges.close(); monitor.shutdownNow(); dispatcher.interrupt(); listeners.clear(); terminalQueues.clear();
    }
}
