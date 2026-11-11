package com.jlshell.program.plugin.loader;

import com.jlshell.program.api.ProgramTerminalService;
import com.jlshell.program.api.ProgramWorkspaceService;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ScopedProgramWorkspaceServiceTest {
    @Test void disposalCancelsOwnedProcessesAndRejectsFurtherAccess() {
        var cancelled = new AtomicInteger();
        var completed = new CompletableFuture<com.jlshell.program.api.ProgramProcessService.Process>();
        var process = new com.jlshell.program.api.ProgramProcessService.Process("process", "session", "RUNNING", "now", null, null, "", "", false, "");
        var raw = new com.jlshell.program.api.ProgramProcessService() {
            public CompletableFuture<Process> start(Request request) { return CompletableFuture.completedFuture(process); }
            public Process get(String id) { return process; }
            public CompletableFuture<Process> await(String id, int seconds) { return completed; }
            public Events events(String id, long after, int limit) { return new Events(List.of(),after,false); }
            public Subscription subscribe(String id, Consumer<Output> listener) { return () -> {}; }
            public boolean cancel(String id) { cancelled.incrementAndGet(); completed.complete(process); return true; }
        };
        var delegate = (ProgramWorkspaceService) Proxy.newProxyInstance(ProgramWorkspaceService.class.getClassLoader(),
                new Class<?>[]{ProgramWorkspaceService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "subscribe" -> (Subscription) () -> {};
                    case "processes" -> raw;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var scoped = new ScopedProgramWorkspaceService(delegate);
        var accessor = scoped.processes();
        accessor.start(new com.jlshell.program.api.ProgramProcessService.Request("session",List.of("pwd"),null,java.util.Map.of(),60,100)).join();
        scoped.close();
        assertEquals(1,cancelled.get());
        assertThrows(IllegalStateException.class,() -> accessor.get("process"));
    }

    @Test void disposesSubscriptionsAndCancelsAnOperationThatArrivesAfterDisposal() {
        var released = new AtomicInteger();
        var cancelled = new AtomicInteger();
        var pending = new CompletableFuture<Operation>();
        var operation = new Operation("pending", "session", "terminal", null, null,
                "SUBMIT", State.QUEUED, 0, "", "now", null);
        var terminal = new ProgramTerminalService() {
            public String sessionId() { return "session"; }
            public String terminalId() { return "terminal"; }
            public CompletableFuture<Snapshot> snapshot(int lines, int characters) { throw new UnsupportedOperationException(); }
            public TerminalEvents events(long after, int limit) { throw new UnsupportedOperationException(); }
            public Subscription subscribe(Consumer<TerminalEvent> listener) { return released::incrementAndGet; }
        };
        var delegate = (ProgramWorkspaceService) Proxy.newProxyInstance(
                ProgramWorkspaceService.class.getClassLoader(), new Class<?>[]{ProgramWorkspaceService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "subscribe" -> (Subscription) released::incrementAndGet;
                    case "terminal" -> terminal;
                    case "enqueue" -> pending;
                    case "operation" -> operation;
                    case "cancelOperation" -> { cancelled.incrementAndGet(); yield true; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var scoped = new ScopedProgramWorkspaceService(delegate);
        var read = scoped.terminal("session", "terminal");
        scoped.subscribe(event -> fail("Disposed listener"));
        read.subscribe(event -> fail("Disposed terminal listener"));
        var result = scoped.enqueue(new Input("session", "terminal", null, null, "SUBMIT", List.of("pwd"), 0, false, 30));
        scoped.close();
        pending.complete(operation);
        assertEquals(operation, result.join());
        assertEquals(3, released.get());
        assertEquals(1, cancelled.get());
        scoped.close();
        assertEquals(3, released.get());
        assertThrows(IllegalStateException.class, () -> read.events(0, 10));
    }
}
