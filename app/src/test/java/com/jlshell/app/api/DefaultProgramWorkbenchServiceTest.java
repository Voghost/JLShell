package com.jlshell.app.api;

import com.google.gson.JsonObject;
import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultProgramWorkbenchServiceTest {
    @Test void targetsOnlyTheExplicitVisibleTerminal() throws Exception {
        String id="rpc-terminal-regression";
        var terminal=mock(TerminalAccess.class);
        when(terminal.paste("hello")).thenReturn(CompletableFuture.completedFuture(null));
        try(var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(id,"Test","host","user",true,true),terminal)) {
            var service=new DefaultProgramWorkbenchService(session -> { throw new AssertionError("Unexpected file access"); });
            JsonObject args=new JsonObject(); args.addProperty("sessionId",id); args.addProperty("text","hello");
            service.invoke("terminal.paste",args).get();
            verify(terminal).paste("hello");
            args.addProperty("sessionId","missing-terminal");
            assertThrows(ExecutionException.class,() -> service.invoke("terminal.paste",args).get());
            verifyNoMoreInteractions(terminal);
        }
        assertTrue(HostExtensionRegistry.terminal(id).isEmpty());
    }
    @Test void rejectsMissingParametersAndAdvertisesMethods() {
        var service=new DefaultProgramWorkbenchService(session -> { throw new AssertionError("Unexpected file access"); });
        assertThrows(ExecutionException.class,() -> service.invoke("terminal.submit",new JsonObject()).get());
        assertTrue(com.jlshell.program.api.ProgramApiCatalog.methodNames().contains("terminal.snapshot"));
        assertTrue(com.jlshell.program.api.ProgramApiCatalog.methodNames().contains("file.readRange"));
    }
}
