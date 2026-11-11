package com.jlshell.app.api;

import com.google.gson.*;
import com.jlshell.api.server.*;
import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.host.*;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.loader.*;
import com.jlshell.program.api.*;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import com.jlshell.ui.service.ConnectionProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRpcTest {
    @TempDir Path directory;
    @Test void servesAtomicEditsProcessesCheckpointsAndContextThroughAuthenticatedRpc() throws Exception {
        String id=UUID.randomUUID().toString();
        var terminal=mock(TerminalAccess.class);
        when(terminal.terminalId()).thenReturn("agent-rpc-terminal");
        var manager=mock(SessionManager.class);
        when(manager.listSessions()).thenReturn(List.of()); when(manager.getSession(any())).thenReturn(Optional.empty());
        var ui=mock(WorkspaceUi.class);
        when(ui.context()).thenReturn(CompletableFuture.completedFuture(new UiContext("saved-local",id,"agent-rpc-terminal",null,"DARK",1200,800)));
        HostExtensionRegistry.workspaceUi(ui); HostExtensionRegistry.bindLocal(id,"saved-local");
        try (var executor=Executors.newVirtualThreadPerTaskExecutor();
             var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(id,"Local","localhost","user",true,true),terminal);
             var service=new DefaultProgramWorkspaceService(mock(ConnectionProfileService.class),manager,executor,session -> new LocalAgentFiles(executor));
             var client=HttpClient.newHttpClient()) {
            var registry=new DefaultProgramApiRegistry();
            var context=mock(ProgramApiContext.class);
            when(context.registry()).thenReturn(registry); when(context.workspace()).thenReturn(service);
            new DefaultProgramApiProvider().activate(context);
            var server=new ApiServer(new ApiServerConfig(0,"agent-rpc-test",true),mock(CapabilityBus.class),registry);
            server.start();
            try {
                URI endpoint=URI.create("http://127.0.0.1:"+server.port()+"/rpc");
                Path path=directory.resolve("code.txt");
                JsonObject file=args("sessionId",id,"path",path.toString(),"contentBase64","");
                call(client,endpoint,"file.write",file);
                assertEquals(0,Files.size(path));
                file.addProperty("contentBase64",Base64.getEncoder().encodeToString("before".getBytes()));
                call(client,endpoint,"file.write",file);
                var original=call(client,endpoint,"file.readVersioned",args("sessionId",id,"path",path.toString())).getAsJsonObject();
                JsonObject patch=args("sessionId",id,"path",path.toString(),"expectedSha256",original.get("sha256").getAsString());
                patch.add("edits",JsonParser.parseString("[{\"oldText\":\"before\",\"newText\":\"after\"}]"));
                call(client,endpoint,"file.applyPatch",patch);
                assertEquals("after",Files.readString(path));
                JsonObject process=args("sessionId",id);
                JsonArray argv=new JsonArray();
                String binary=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
                argv.add(Path.of(System.getProperty("java.home"),"bin",binary).toString()); argv.add("-version");
                process.add("argv",argv);
                String processId=call(client,endpoint,"process.start",process).getAsJsonObject().get("processId").getAsString();
                assertEquals("SUCCEEDED",call(client,endpoint,"process.await",args("processId",processId)).getAsJsonObject().get("state").getAsString());
                JsonObject job=args("title","Agent RPC");
                JsonArray steps=new JsonArray(); steps.add(args("stepId","build","title","Build","sessionId",id)); job.add("steps",steps);
                String jobId=call(client,endpoint,"job.create",job).getAsJsonObject().get("jobId").getAsString();
                JsonObject checkpoint=args("jobId",jobId); checkpoint.addProperty("expectedRevision",0); checkpoint.add("data",args("stage","build"));
                assertEquals(1,call(client,endpoint,"job.checkpoint.save",checkpoint).getAsJsonObject().get("revision").getAsLong());
                assertEquals("build",call(client,endpoint,"job.checkpoint.get",args("jobId",jobId)).getAsJsonObject().getAsJsonObject("data").get("stage").getAsString());
                var application=call(client,endpoint,"agent.context",new JsonObject()).getAsJsonObject();
                assertEquals(id,application.getAsJsonObject("ui").get("activeSessionId").getAsString());
                assertFalse(application.has("terminalSnapshot"));
            } finally { server.stop(); }
        } finally { HostExtensionRegistry.workspaceUi(null); HostExtensionRegistry.unbind(id); }
    }
    private static JsonObject args(String... fields) {
        JsonObject value=new JsonObject();
        for (int index=0;index<fields.length;index+=2) value.addProperty(fields[index],fields[index+1]);
        return value;
    }
    private static JsonElement call(HttpClient client,URI endpoint,String method,JsonObject parameters) throws Exception {
        JsonObject body=new JsonObject(); body.addProperty("jsonrpc","2.0"); body.addProperty("id",1); body.addProperty("method",method); body.add("params",parameters);
        var request=HttpRequest.newBuilder(endpoint).header("Authorization","Bearer agent-rpc-test")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        var decoded=JsonParser.parseString(response.body()).getAsJsonObject();
        assertFalse(decoded.has("error"),response.body());
        return decoded.get("result");
    }
}
