package com.jlshell.app.api;

import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.program.api.ProgramProcessService.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisabledOnOs(OS.WINDOWS)
class AgentProcessTest {
    @TempDir Path directory;
    @Test void streamsSeparateOutputAndSupportsDirectoryEnvironmentAndExitCodes() throws Exception {
        String sessionId=UUID.randomUUID().toString();
        var terminal=mock(TerminalAccess.class);
        when(terminal.terminalId()).thenReturn(UUID.randomUUID().toString());
        HostExtensionRegistry.bindLocal(sessionId,"local-fixture");
        var manager=mock(SessionManager.class);
        when(manager.getSession(any())).thenReturn(Optional.empty());
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(sessionId,"Local","localhost","user",true,true),terminal);
             var service=new DefaultProgramProcessService(manager,executor)) {
            var request=new Request(sessionId,List.of("/bin/sh","-c","printf '%s\\n' \"$AGENT_TEST\"; pwd; printf 'warning' >&2; exit 7"),directory.toString(),Map.of("AGENT_TEST","value with spaces"),10,65536);
            var started=service.start(request).get();
            var result=service.await(started.processId(),10).get();
            assertEquals("FAILED",result.state());
            assertEquals(7,result.exitCode());
            assertTrue(result.stdout().contains("value with spaces"));
            assertTrue(result.stdout().contains(directory.toRealPath().toString()));
            assertEquals("warning",result.stderr());
            assertFalse(result.truncated());
            assertTrue(service.events(started.processId(),0,256).events().stream().anyMatch(event -> event.stream().equals("STDERR")));
        } finally { HostExtensionRegistry.unbind(sessionId); }
    }
    @Test void cancellationAndTimeoutCloseProcessesWithoutClosingTheTerminal() throws Exception {
        String sessionId=UUID.randomUUID().toString();
        var terminal=mock(TerminalAccess.class);
        when(terminal.terminalId()).thenReturn(UUID.randomUUID().toString());
        HostExtensionRegistry.bindLocal(sessionId,"local-fixture");
        var manager=mock(SessionManager.class);
        when(manager.getSession(any())).thenReturn(Optional.empty());
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(sessionId,"Local","localhost","user",true,true),terminal);
             var service=new DefaultProgramProcessService(manager,executor)) {
            var first=service.start(new Request(sessionId,List.of("/bin/sh","-c","sleep 30"),null,Map.of(),10,100)).get();
            assertTrue(service.cancel(first.processId()));
            assertEquals("CANCELLED",service.await(first.processId(),3).get().state());
            var second=service.start(new Request(sessionId,List.of("/bin/sh","-c","sleep 30"),null,Map.of(),1,100)).get();
            assertEquals("TIMED_OUT",service.await(second.processId(),5).get().state());
            assertTrue(HostExtensionRegistry.terminal(sessionId).isPresent());
        } finally { HostExtensionRegistry.unbind(sessionId); }
    }
    @Test void drainsOutputAfterRetentionFillsAndQuotesRemoteArgvLiterally() throws Exception {
        String sessionId=UUID.randomUUID().toString();
        var terminal=mock(TerminalAccess.class);
        when(terminal.terminalId()).thenReturn(UUID.randomUUID().toString());
        HostExtensionRegistry.bindLocal(sessionId,"local-fixture");
        var manager=mock(SessionManager.class);
        when(manager.getSession(any())).thenReturn(Optional.empty());
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(sessionId,"Local","localhost","user",true,true),terminal);
             var service=new DefaultProgramProcessService(manager,executor)) {
            var started=service.start(new Request(sessionId,List.of("/bin/sh","-c","i=0; while [ $i -lt 30000 ]; do printf 'stdout1234567890'; printf 'stderr1234567890' >&2; i=$((i+1)); done"),null,Map.of(),15,100)).get();
            var result=service.await(started.processId(),15).get();
            assertEquals("SUCCEEDED",result.state());
            assertEquals(100,result.stdout().length()+result.stderr().length());
            assertTrue(result.truncated());
            assertTrue(service.events(started.processId(),0,256).truncated());
            var request=new Request(sessionId,List.of("printf","%s","$(touch should-not-exist); 'quoted'"),directory.toString(),Map.of("A","hello world"),5,1000);
            var builder=new ProcessBuilder("/bin/sh","-c",DefaultProgramProcessService.remoteCommand(request)).directory(directory.toFile());
            var process=builder.start();
            assertEquals("$(touch should-not-exist); 'quoted'",new String(process.getInputStream().readAllBytes()));
            assertEquals(0,process.waitFor());
            assertFalse(java.nio.file.Files.exists(directory.resolve("should-not-exist")));
        } finally { HostExtensionRegistry.unbind(sessionId); }
    }
}
