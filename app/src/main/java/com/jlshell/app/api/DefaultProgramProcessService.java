package com.jlshell.app.api;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.CommandChannel;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.program.api.ProgramProcessService;
import com.jlshell.program.api.ProgramWorkspaceService.Subscription;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Dedicated exec processes. Readers always drain both streams, even after output retention fills. */
final class DefaultProgramProcessService implements ProgramProcessService, AutoCloseable {
    private final SessionManager sessions;
    private final Executor executor;
    private final ConcurrentMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-process-timeouts").factory());
    private volatile boolean closed;
    private static final int OUTPUT_BUDGET = 8 * 1024 * 1024;
    private final java.util.concurrent.atomic.AtomicInteger retainedCharacters = new java.util.concurrent.atomic.AtomicInteger();

    DefaultProgramProcessService(SessionManager sessions, Executor executor) {
        this.sessions = sessions;
        this.executor = executor;
    }

    public CompletableFuture<Process> start(Request request) {
        try {
            validate(request);
            synchronized (runs) {
                if (closed) throw new IllegalStateException("Process service closed");
                if (runs.values().stream().filter(run -> !run.finished.get()).count() >= 64)
                    throw new IllegalStateException("Too many active processes");
                if (runs.size() >= 256 || retainedCharacters.get() >= OUTPUT_BUDGET) runs.values().stream().filter(run -> run.finished.get())
                        .sorted(Comparator.comparing(run -> run.startedAt)).limit(128)
                        .forEach(this::removeFinished);
                Run run = new Run(request);
                runs.put(run.id, run);
                run.timeout = timer.schedule(() -> stop(run, "TIMED_OUT", "Process timed out"), request.timeoutSeconds(), TimeUnit.SECONDS);
                try { CompletableFuture.runAsync(() -> launch(run), executor); }
                catch (RuntimeException error) {
                    run.channelReady.completeExceptionally(error);
                    finish(run,"FAILED",null,message(error));
                    return CompletableFuture.failedFuture(error);
                }
                return CompletableFuture.completedFuture(snapshot(run));
            }
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    private void validate(Request request) {
        Objects.requireNonNull(request);
        if (request.sessionId() == null || request.sessionId().isBlank() || request.argv().isEmpty() || request.argv().size() > 1000)
            throw new IllegalArgumentException("A session and 1..1000 argv entries are required");
        if (request.argv().getFirst().isBlank() || request.argv().stream().anyMatch(value -> value.indexOf('\0') >= 0)
                || request.argv().stream().mapToLong(String::length).sum() > 1_000_000)
            throw new IllegalArgumentException("Invalid argv");
        if (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 3600 || request.maxOutputCharacters() < 1 || request.maxOutputCharacters() > 4_000_000)
            throw new IllegalArgumentException("Invalid timeout or output limit");
        if (request.directory() != null && request.directory().indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid working directory");
        if (request.environment().size() > 256 || request.environment().entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getValue().indexOf('\0') >= 0)
                || request.environment().entrySet().stream().mapToLong(entry -> entry.getKey().length() + entry.getValue().length()).sum() > 262144)
            throw new IllegalArgumentException("Invalid environment");
    }

    private void launch(Run run) {
        Request request = run.request;
        if (run.finished.get() || request == null || run.stopping.get()) {
            run.channelReady.complete(null);
            return;
        }
        try {
            CommandChannel channel;
            var session = sessions.getSession(parseSessionId(run.sessionId));
            if (session.isPresent()) {
                channel = session.get().startCommand(new CommandRequest(remoteCommand(request),
                        Duration.ofSeconds(request.timeoutSeconds()), false, Map.of())).join();
            } else {
                if (!HostExtensionRegistry.localSession(run.sessionId))
                    throw new IllegalArgumentException("Session is not an open local shell");
                channel = localCommand(request);
            }
            run.channel = channel;
            run.channelReady.complete(channel);
            synchronized (run) {
                if (run.finished.get() || run.stopping.get()) return;
                run.state = "RUNNING";
            }
            var stdout = CompletableFuture.runAsync(() -> drain(run, channel.stdout(), "STDOUT"), executor);
            var stderr = CompletableFuture.runAsync(() -> drain(run, channel.stderr(), "STDERR"), executor);
            channel.completion().whenComplete((exit, error) -> CompletableFuture.allOf(stdout, stderr).whenComplete((ignored, readError) -> {
                try {
                    Throwable failure = error == null ? readError : error;
                    finish(run, failure == null && exit != null && exit == 0 ? "SUCCEEDED" : "FAILED", exit,
                            failure == null ? exit == null ? "Exit status unavailable" : exit == 0 ? "" : "Process exited with code " + exit : message(failure));
                } finally { channel.close(); }
            }));
        } catch (Throwable error) {
            run.channelReady.completeExceptionally(error);
            finish(run, "FAILED", null, message(error));
            CommandChannel channel = run.channel;
            if (channel != null) channel.close();
        }
    }

    private static SessionId parseSessionId(String id) { return new SessionId(UUID.fromString(id)); }
    static String remoteCommand(Request request) {
        StringBuilder command = new StringBuilder();
        if (request.directory() != null && !request.directory().isBlank()) command.append("cd ").append(quote(request.directory())).append(" && ");
        command.append("exec env --");
        request.environment().forEach((key, value) -> command.append(' ').append(quote(key + "=" + value)));
        request.argv().forEach(value -> command.append(' ').append(quote(value)));
        return command.toString();
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static CommandChannel localCommand(Request request) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(request.argv());
        if (request.directory() != null && !request.directory().isBlank()) builder.directory(Path.of(request.directory()).toFile());
        builder.environment().putAll(request.environment());
        java.lang.Process process = builder.start();
        process.getOutputStream().close();
        return new CommandChannel() {
            public InputStream stdout() { return process.getInputStream(); }
            public InputStream stderr() { return process.getErrorStream(); }
            public CompletableFuture<Integer> completion() { return process.onExit().thenApply(java.lang.Process::exitValue); }
            public void cancel() {
                process.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                close();
            }
            public void close() {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
        };
    }

    private void drain(Run run, InputStream stream, String name) {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) if (count > 0) output(run, name, new String(buffer, 0, count));
        } catch (IOException error) {
            if (!run.finished.get()) throw new CompletionException(error);
        }
    }
    private void output(Run run, String stream, String text) {
        synchronized (run) {
            if (run.finished.get()) return;
            int available = run.outputLimit - run.stdout.length() - run.stderr.length();
            int wanted = Math.min(available, text.length());
            int count;
            while (true) {
                int total = retainedCharacters.get();
                count = Math.min(wanted, Math.max(0, OUTPUT_BUDGET - total));
                if (retainedCharacters.compareAndSet(total,total + count)) break;
            }
            String retained = text.substring(0, count);
            (stream.equals("STDOUT") ? run.stdout : run.stderr).append(retained);
            if (retained.length() < text.length()) run.truncated = true;
            Output event = new Output(++run.sequence, Instant.now().toString(), stream, text);
            run.events.addLast(event);
            run.eventCharacters += text.length();
            while (run.events.size() > 128 || run.eventCharacters > 65536) run.eventCharacters -= run.events.removeFirst().text().length();
            if (!run.delivery.offer(event)) { run.delivery.poll(); run.delivery.offer(event); }
        }
    }
    private void finish(Run run, String state, Integer exit, String error) {
        synchronized (run) {
            if (run.stopping.get() && !state.equals("CANCELLED") && !state.equals("TIMED_OUT")) return;
            if (!run.finished.compareAndSet(false, true)) return;
            run.state = state;
            run.exitCode = exit;
            run.error = error;
            run.finishedAt = Instant.now().toString();
            run.request = null;
            if (run.timeout != null) run.timeout.cancel(false);
            if (state.equals("FAILED") || state.equals("TIMED_OUT")) HostExtensionRegistry.error("PROCESS",run.sessionId,null,error);
            run.result.complete(snapshot(run));
        }
    }
    private boolean stop(Run run, String state, String message) {
        synchronized (run) {
            if (run.finished.get() || !run.stopping.compareAndSet(false,true)) return false;
            run.state = "CANCELLING";
            run.error = message;
            if (run.timeout != null) run.timeout.cancel(false);
        }
        run.channelReady.whenComplete((channel,error) -> {
            Runnable cancel = () -> {
                try { if (channel != null) channel.cancel(); }
                finally { finish(run,state,null,message); }
            };
            try { CompletableFuture.runAsync(cancel,executor); }
            catch (RejectedExecutionException shuttingDown) { Thread.ofVirtual().name("agent-process-close").start(cancel); }
        });
        return true;
    }
    private Run require(String id) {
        Run run = runs.get(id);
        if (run == null) throw new IllegalArgumentException("Process not found");
        return run;
    }
    public Process get(String id) { return snapshot(require(id)); }
    private Process snapshot(Run run) {
        synchronized (run) {
            return new Process(run.id, run.sessionId, run.state, run.startedAt, run.finishedAt,
                    run.exitCode, run.stdout.toString(), run.stderr.toString(), run.truncated, run.error);
        }
    }
    public CompletableFuture<Process> await(String id, int timeout) {
        if (timeout < 1 || timeout > 3600) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid wait timeout"));
        return require(id).result.thenApply(value -> value).orTimeout(timeout, TimeUnit.SECONDS);
    }
    public Events events(String id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid event page");
        Run run = require(id);
        synchronized (run) {
            var page = run.events.stream().filter(event -> event.sequence() > after).limit(limit).toList();
            return new Events(page, page.isEmpty() ? Math.max(after, run.sequence) : page.getLast().sequence(),
                    !run.events.isEmpty() && after < run.events.getFirst().sequence() - 1);
        }
    }
    public Subscription subscribe(String id, Consumer<Output> listener) {
        Run run = require(id);
        run.listeners.add(Objects.requireNonNull(listener));
        return () -> run.listeners.remove(listener);
    }
    public boolean cancel(String id) { return stop(require(id), "CANCELLED", "Cancelled"); }
    void cancelDisconnectedSessions(Set<String> connected) {
        runs.values().stream().filter(run -> !run.finished.get() && !connected.contains(run.sessionId))
                .forEach(run -> stop(run,"CANCELLED","Owning session disconnected"));
    }
    private void removeFinished(Run run) {
        synchronized (run) {
            if (run.finished.get() && runs.remove(run.id,run)) retainedCharacters.addAndGet(-run.stdout.length()-run.stderr.length());
        }
    }
    List<Summary> summaries() {
        return runs.values().stream().sorted(Comparator.comparing((Run run) -> run.startedAt).reversed()).limit(64).map(run -> {
            synchronized (run) { return new Summary(run.id,run.sessionId,run.state,run.startedAt,run.finishedAt,run.exitCode); }
        }).toList();
    }
    public void close() {
        synchronized (runs) {
            closed = true;
            runs.values().forEach(run -> stop(run, "CANCELLED", "Application closed"));
            timer.shutdownNow();
        }
    }
    private static String message(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
    private static final class Run {
        final String id = UUID.randomUUID().toString();
        volatile Request request;
        final String sessionId;
        final int outputLimit;
        final String startedAt = Instant.now().toString();
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean stopping = new AtomicBoolean();
        final CompletableFuture<CommandChannel> channelReady = new CompletableFuture<>();
        final CompletableFuture<Process> result = new CompletableFuture<>();
        final StringBuilder stdout = new StringBuilder(), stderr = new StringBuilder();
        final ArrayDeque<Output> events = new ArrayDeque<>();
        final ArrayBlockingQueue<Output> delivery = new ArrayBlockingQueue<>(256);
        final CopyOnWriteArrayList<Consumer<Output>> listeners = new CopyOnWriteArrayList<>();
        long sequence;
        int eventCharacters;
        boolean truncated;
        String state = "STARTING", finishedAt, error = "";
        Integer exitCode;
        volatile CommandChannel channel;
        volatile ScheduledFuture<?> timeout;
        Run(Request request) {
            this.request = request;
            this.sessionId = request.sessionId();
            this.outputLimit = request.maxOutputCharacters();
            Thread.ofVirtual().name("agent-process-events").start(() -> {
                try {
                    while (!finished.get() || !delivery.isEmpty()) {
                        Output event = delivery.poll(100, TimeUnit.MILLISECONDS);
                        if (event != null) for (var listener : listeners) {
                            try { listener.accept(event); } catch (RuntimeException error) { HostExtensionRegistry.report(error); }
                        }
                    }
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { listeners.clear(); }
            });
        }
    }
}
