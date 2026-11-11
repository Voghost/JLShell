package com.jlshell.app.api;

import com.jlshell.core.model.*;
import com.jlshell.core.service.SessionManager;
import com.jlshell.data.config.DatabaseFactory;
import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.plugin.loader.WorkspaceUi;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import com.jlshell.ui.service.ConnectionProfileService;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkspaceRecoveryTest {
    @TempDir Path directory;
    @Test void persistsCheckpointRecoversInterruptedStepsAndRequiresExplicitRebinding() throws Exception {
        Jdbi database=Jdbi.create("jdbc:sqlite:"+directory.resolve("agent.db"));
        DatabaseFactory.initSchema(database);
        var store=new SqliteWorkspaceJobStore(database);
        var manager=mock(SessionManager.class);
        when(manager.listSessions()).thenReturn(List.of());
        var profiles=mock(ConnectionProfileService.class);
        var access=mock(TerminalAccess.class);
        when(access.terminalId()).thenReturn("old-terminal");
        HostExtensionRegistry.bind("old-session","saved-profile",Presentation.TAB);
        String jobId;
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session("old-session","Old","host","user",true,true),access)) {
            try (var service=new DefaultProgramWorkspaceService(profiles,manager,executor,id -> { throw new AssertionError(); },store)) {
                var job=service.createJob("Agent workflow",List.of(
                        new Step("done","Done","old-session",null,State.QUEUED,0,""),
                        new Step("pending","Pending","old-session",null,State.QUEUED,0,"")));
                jobId=job.jobId();
                service.updateStep(jobId,"done",State.SUCCEEDED,1,"Done");
                service.updateStep(jobId,"pending",State.RUNNING,0.4,"In progress");
                assertEquals(1,service.checkpoint(jobId,0,"{\"stage\":\"build\"}").get().revision());
                assertThrows(ExecutionException.class,() -> service.checkpoint(jobId,0,"{}").get());
                assertEquals(1,service.checkpoint(jobId).get().revision());
            }
            try (var service=new DefaultProgramWorkspaceService(profiles,manager,executor,id -> { throw new AssertionError(); },new SqliteWorkspaceJobStore(database))) {
                var restored=service.job(jobId);
                assertEquals(State.INTERRUPTED,restored.state());
                assertEquals(State.SUCCEEDED,restored.steps().getFirst().state());
                assertEquals("saved-profile",restored.steps().getLast().connectionId());
                assertTrue(service.checkpoint(jobId).get().recovered());
                assertTrue(service.checkpoint(jobId).get().dataJson().contains("build"));
                assertThrows(IllegalArgumentException.class,() -> service.resumeJob(jobId,List.of()));
                var resumed=service.resumeJob(jobId,List.of(new ResumeTarget("pending","old-session",null)));
                assertEquals(State.QUEUED,resumed.state());
                assertEquals(State.SUCCEEDED,resumed.steps().getFirst().state());
                assertFalse(service.checkpoint(jobId).get().recovered());
                assertEquals(2,service.checkpoint(jobId,1,"{\"stage\":\"retry\"}").get().revision());
            }
        } finally { HostExtensionRegistry.unbind("old-session"); }
    }

    @Test void contextReportsUiSelectionConnectionTimingAndErrorsWithoutReadingTerminalByDefault() throws Exception {
        String sessionId=UUID.randomUUID().toString();
        Instant connected=Instant.now().minusSeconds(90);
        var manager=mock(SessionManager.class);
        when(manager.listSessions()).thenReturn(List.of(new SessionDescriptor(new SessionId(UUID.fromString(sessionId)),"Server",
                new ConnectionTarget("test-host",22,"test-user",null,null),SessionState.CONNECTED,connected)));
        var terminal=mock(TerminalAccess.class);
        when(terminal.terminalId()).thenReturn("context-terminal");
        when(terminal.snapshot(100,65536)).thenReturn(CompletableFuture.completedFuture(new TerminalAccess.Snapshot(sessionId,"screen","history","selection","/project",80,24,1,false)));
        var ui=mock(WorkspaceUi.class);
        when(ui.context()).thenReturn(CompletableFuture.completedFuture(new UiContext("selected-other-profile",sessionId,"context-terminal","project-id","DARK",1200,800)));
        HostExtensionRegistry.workspaceUi(ui);
        HostExtensionRegistry.bind(sessionId,"active-profile",Presentation.TAB);
        HostExtensionRegistry.error("AUTHENTICATION",sessionId,"active-profile","Authentication failed");
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(sessionId,"Server","test-host","test-user",true,true),terminal);
             var service=new DefaultProgramWorkspaceService(mock(ConnectionProfileService.class),manager,executor,id -> { throw new AssertionError(); })) {
            var context=service.context(new ContextRequest(false,100,65536)).get();
            assertEquals("selected-other-profile",context.ui().selectedConnectionId());
            assertEquals(sessionId,context.ui().activeSessionId());
            assertEquals(connected.toString(),context.sessions().getFirst().connectedAt());
            assertTrue(context.sessions().getFirst().connectedSeconds()>=90);
            assertEquals("AUTHENTICATION",context.sessions().getFirst().lastError().category());
            assertNull(context.terminalSnapshot());
            verify(terminal,never()).snapshot(anyInt(),anyInt());
            assertEquals("screen",service.context(new ContextRequest(true,100,65536)).get().terminalSnapshot().screen());
            var rpc=WorkspaceRpcMethods.invoke(service,"agent.context",new com.google.gson.JsonObject()).get();
            assertEquals("1",rpc.getAsJsonObject().get("schemaVersion").getAsString());
        } finally {
            HostExtensionRegistry.workspaceUi(null);
            HostExtensionRegistry.unbind(sessionId);
        }
    }
}
