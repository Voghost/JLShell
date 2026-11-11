package com.jlshell.app.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.api.server.ApiServer;
import com.jlshell.api.server.ApiServerConfig;
import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.plugin.loader.WorkspaceUi;
import com.jlshell.program.api.*;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import com.jlshell.ui.service.ConnectionProfileService;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultProgramWorkspaceServiceTest {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Registration> registrations = new ArrayList<>();
    private DefaultProgramWorkspaceService service;
    private final com.jlshell.plugin.api.capability.FileExplorer fileService = mock(com.jlshell.plugin.api.capability.FileExplorer.class);

    @BeforeEach void setup() {
        var manager = mock(SessionManager.class);
        when(manager.listSessions()).thenReturn(List.of());
        var profiles = mock(ConnectionProfileService.class);
        when(profiles.listProfiles()).thenReturn(List.of());
        service = new DefaultProgramWorkspaceService(profiles, manager, executor, id -> fileService);
    }

    @AfterEach void cleanup() {
        service.close();
        registrations.forEach(Registration::close);
        HostExtensionRegistry.workspaceUi(null);
        executor.shutdownNow();
    }

    private FakeTerminal terminal(String session, String id) {
        var target = new FakeTerminal(session, id);
        registrations.add(HostExtensionRegistry.terminal(
                new HostExtensions.Session(session, session, "test-host", "test-user", true, false), target));
        return target;
    }

    private Input input(String session, String terminal, String command) {
        return new Input(session, terminal, null, null, "SUBMIT", List.of(command), 0, false, 5);
    }

    @Test void rejectsAmbiguousAndCrossSessionTargets() throws Exception {
        terminal("workspace-target-a", "terminal-a1");
        terminal("workspace-target-a", "terminal-a2");
        terminal("workspace-target-b", "terminal-b1");
        assertThrows(ExecutionException.class, () -> service.enqueue(input("workspace-target-a", null, "pwd")).get());
        assertThrows(ExecutionException.class, () -> service.enqueue(input("workspace-target-a", "terminal-b1", "pwd")).get());
        assertEquals(2, service.terminals("workspace-target-a").size());
        assertEquals("terminal-a1", service.terminal("workspace-target-a", "terminal-a1").snapshot(10, 100).get().terminalId());
        assertTrue(service.get("workspace-target-a").get().ready().contains(Ready.SHELL));
    }

    @Test void serializesInputsAndCancelsQueuedWork() throws Exception {
        var target = terminal("workspace-fifo", "terminal-fifo");
        target.pending = new CompletableFuture<>();
        var first = service.enqueue(input(target.session, target.id, "first")).get();
        assertTrue(target.entered.await(2, TimeUnit.SECONDS));
        var second = service.enqueue(input(target.session, target.id, "second")).get();
        var third = service.enqueue(input(target.session, target.id, "third")).get();
        assertTrue(service.cancelOperation(second.operationId()));
        target.pending.complete(null);
        assertEquals(State.SUCCEEDED, service.awaitOperation(first.operationId(), 3).get().state());
        assertEquals(State.SUCCEEDED, service.awaitOperation(third.operationId(), 3).get().state());
        assertEquals(List.of("first", "third"), target.inputs);
        assertEquals(State.CANCELLED, service.operation(second.operationId()).state());
    }

    @Test void reportsCommandExitAndJobFailure() throws Exception {
        var target = terminal("workspace-command", "terminal-command");
        target.exitCode = 7;
        var job = service.createJob("Inspect", List.of(new Step("inspect", "Inspect", target.session, target.id, State.QUEUED, 0, "")));
        var request = new Input(target.session, target.id, job.jobId(), "inspect", "SUBMIT", List.of("false"), 0, true, 5);
        var operation = service.enqueue(request).get();
        var result = service.awaitOperation(operation.operationId(), 3).get();
        assertEquals(State.FAILED, result.state());
        assertTrue(result.message().contains("7"));
        assertEquals(State.FAILED, service.job(job.jobId()).state());
        assertThrows(IllegalStateException.class, () -> service.updateStep(job.jobId(), "inspect", State.RUNNING, 0, "retry"));
    }

    @Test void closedTargetNeverRedirectsToAnotherTerminal() throws Exception {
        var target = terminal("workspace-stale", "terminal-stale");
        target.pending = new CompletableFuture<>();
        var first = service.enqueue(input(target.session, target.id, "first")).get();
        assertTrue(target.entered.await(2, TimeUnit.SECONDS));
        var second = service.enqueue(input(target.session, target.id, "second")).get();
        registrations.getFirst().close();
        var replacement = terminal("workspace-replacement", "terminal-replacement");
        target.pending.complete(null);
        service.awaitOperation(first.operationId(), 3).get();
        assertNotEquals(State.SUCCEEDED, service.awaitOperation(second.operationId(), 3).get().state());
        assertTrue(replacement.inputs.isEmpty());
    }

    @Test void concurrentReuseOpensOnceAndSelectionCanCancel() throws Exception {
        var ui = mock(WorkspaceUi.class);
        var pending = new CompletableFuture<String>();
        when(ui.open(any())).thenReturn(pending);
        when(ui.choose(any())).thenReturn(CompletableFuture.completedFuture(new Selection(true, List.of())));
        HostExtensionRegistry.workspaceUi(ui);
        var request = new OpenRequest("saved-connection", Presentation.TAB, Reuse.IF_CONNECTED, false);
        var first = service.open(request);
        var second = service.open(request);
        terminal("workspace-open", "terminal-open");
        pending.complete("workspace-open");
        assertEquals(first.get(), second.get());
        assertEquals("workspace-open", service.open(request).get().sessionId());
        verify(ui, times(1)).open(request);
        assertTrue(service.choose(new SelectionRequest("Choose", "", false, List.of())).get().cancelled());
        HostExtensionRegistry.unbind("workspace-open");
    }

    @Test void boundsEventsAndPreservesReplayTimestamps() {
        var target = terminal("workspace-events-test", "terminal-events-test");
        var replay = service.terminalEvents(target.session, target.id, 0, 128);
        assertEquals("1970-01-01T00:00:00Z", replay.events().getFirst().time());
        for (int index = 0; index < 600; index++) service.emit("TEST", null, null, "event");
        var events = service.events(0, 512);
        assertTrue(events.truncated());
        assertEquals(512, events.events().size());
        assertTrue(service.events(events.nextSequence(), 512).events().isEmpty());
    }

    @Test void providerExposesWorkspaceThroughAuthenticatedHttp() throws Exception {
        var target = terminal("workspace-http", "terminal-http");
        var registry = new DefaultProgramApiRegistry();
        var context = mock(ProgramApiContext.class);
        when(context.registry()).thenReturn(registry);
        when(context.workspace()).thenReturn(service);
        new DefaultProgramApiProvider().activate(context);
        try (var client = HttpClient.newHttpClient()) {
            var server = new ApiServer(new ApiServerConfig(0, "workspace-test-token", true), mock(CapabilityBus.class), registry);
            server.start();
            try {
                var endpoint = URI.create("http://127.0.0.1:" + server.port() + "/rpc");
                var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"terminal.enqueue\",\"params\":{\"sessionId\":\"workspace-http\",\"terminalId\":\"terminal-http\",\"commands\":[\"pwd\"]}}";
                var request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer workspace-test-token")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
                assertEquals(target.id, result.get("terminalId").getAsString());
                assertEquals(State.SUCCEEDED, service.awaitOperation(result.get("operationId").getAsString(), 3).get().state());
                var unauthorized = HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                assertEquals(401, client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertTrue(ProgramApiCatalog.methodNames().containsAll(WorkspaceRpcCatalog.definitions().stream().map(ProgramApiDefinition::method).toList()));
            } finally {
                server.stop();
            }
        }
    }

    @Test void transferReportsProgressAndCooperativeCancellation() throws Exception {
        var target = terminal("workspace-transfer", "terminal-transfer");
        var started = new CountDownLatch(1);
        var transfer = new CompletableFuture<Void>();
        var cancelled = new java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier>();
        when(fileService.upload(any(), eq("/remote/data"), any(), any())).thenAnswer(call -> {
            Consumer<com.jlshell.plugin.api.capability.FileExplorer.Progress> progress = call.getArgument(2);
            cancelled.set(call.getArgument(3));
            progress.accept(new com.jlshell.plugin.api.capability.FileExplorer.Progress(50, 100));
            started.countDown();
            return transfer;
        });
        var operation = service.files(target.session).upload("/local/data", "/remote/data", null, null).get();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(0.5, service.operation(operation.operationId()).progress());
        assertTrue(service.cancelOperation(operation.operationId()));
        assertTrue(cancelled.get().getAsBoolean());
        assertEquals(State.CANCELLED, service.awaitOperation(operation.operationId(), 3).get().state());
    }

    @Test void reconnectReturnsNewIdsAndAnEventWithTheOldId() throws Exception {
        var original = terminal("workspace-old", "terminal-old");
        HostExtensionRegistry.bind(original.session, "reconnect-profile", Presentation.TAB);
        var ui = mock(WorkspaceUi.class);
        when(ui.closeSession(original.session)).thenAnswer(call -> {
            registrations.getFirst().close();
            return CompletableFuture.completedFuture(null);
        });
        when(ui.open(any())).thenAnswer(call -> {
            terminal("workspace-new", "terminal-new");
            return CompletableFuture.completedFuture("workspace-new");
        });
        HostExtensionRegistry.workspaceUi(ui);
        var replacement = service.reconnect(original.session, false).get();
        assertEquals("workspace-new", replacement.sessionId());
        assertEquals(List.of("terminal-new"), replacement.terminalIds());
        assertThrows(ExecutionException.class, () -> service.get(original.session).get());
        assertTrue(service.events(0, 512).events().stream().anyMatch(event ->
                event.type().equals("RECONNECTED") && event.relatedId().equals(original.session)));
        HostExtensionRegistry.unbind(replacement.sessionId());
    }

    @Test void validatesJobTargetAndCancelsAllUnfinishedSteps() {
        var first = terminal("workspace-job-first", "terminal-job-first");
        var second = terminal("workspace-job-second", "terminal-job-second");
        var job = service.createJob("Deploy", List.of(
                new Step("first", "First", first.session, first.id, State.QUEUED, 0, ""),
                new Step("second", "Second", second.session, second.id, State.QUEUED, 0, "")));
        var wrong = new Input(second.session, second.id, job.jobId(), "first", "SUBMIT", List.of("pwd"), 0, false, 5);
        assertThrows(ExecutionException.class, () -> service.enqueue(wrong).get());
        service.updateStep(job.jobId(), "first", State.FAILED, 0, "Failed");
        var cancelled = service.cancelJob(job.jobId());
        assertEquals(State.CANCELLED, cancelled.state());
        assertEquals(State.FAILED, cancelled.steps().getFirst().state());
        assertEquals(State.CANCELLED, cancelled.steps().getLast().state());
        assertTrue(second.inputs.isEmpty());
    }

    private static final class FakeTerminal implements TerminalAccess {
        final String session;
        final String id;
        final List<String> inputs = new CopyOnWriteArrayList<>();
        final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger sequence = new AtomicInteger();
        volatile CompletableFuture<Void> pending;
        volatile int exitCode;
        FakeTerminal(String session, String id) { this.session = session; this.id = id; }
        public String sessionId() { return session; }
        public String terminalId() { return id; }
        public boolean shellReady() { return true; }
        public boolean commandTrackingAvailable() { return true; }
        public CompletableFuture<Snapshot> snapshot(int lines, int chars) { return CompletableFuture.completedFuture(new Snapshot(session, "", "", "", "/", 80, 24, 0, false)); }
        public CompletableFuture<Void> paste(String text) { return submit(text); }
        public CompletableFuture<Void> sendKey(Key key) { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Void> submit(String text) {
            inputs.add(text);
            entered.countDown();
            listeners.forEach(listener -> listener.accept(new Event(sequence.incrementAndGet(), Instant.now(), EventKind.COMMAND_STARTED, "", null)));
            listeners.forEach(listener -> listener.accept(new Event(sequence.incrementAndGet(), Instant.now(), EventKind.COMMAND_FINISHED, "", exitCode)));
            return pending == null ? CompletableFuture.completedFuture(null) : pending;
        }
        public CompletableFuture<Integer> submitBatch(List<String> commands, java.time.Duration interval, Consumer<Integer> progress) { throw new UnsupportedOperationException(); }
        public Registration subscribe(Consumer<Event> listener) { listeners.add(listener); return () -> listeners.remove(listener); }
        public EventPage events(long after, int limit) { return new EventPage(List.of(new Event(1, Instant.EPOCH, EventKind.OUTPUT, "ready", null)), sequence.get(), false); }
    }
}
