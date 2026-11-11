package com.jlshell.program.api;

import com.google.gson.*;
import java.util.List;

/** Discoverable RPC contracts for application-wide workspace control. */
public final class WorkspaceRpcCatalog {
    private WorkspaceRpcCatalog() {}
    public static List<ProgramApiDefinition> definitions() {
        return List.of(
            method("connection.list","Search saved connection metadata.",false,"{\"text\":\"web\",\"projectId\":\"optional\",\"folderId\":\"optional\"}"),
            method("connection.get","Read one saved connection without credentials.",false,"{\"connectionId\":\"connection-id\"}","connectionId"),
            method("session.open","Open or reuse a connection, optionally creating a visible terminal tab.",false,"{\"connectionId\":\"connection-id\",\"presentation\":\"TAB\",\"reuse\":\"IF_CONNECTED\",\"focus\":false}","connectionId"),
            method("session.get","Get workspace session state and readiness.",true,"{\"sessionId\":\"session-id\"}","sessionId"),
            method("session.awaitReady","Wait for CONNECTED, TERMINAL or SHELL readiness.",true,"{\"sessionId\":\"session-id\",\"ready\":\"TERMINAL\",\"timeoutSeconds\":30}","sessionId"),
            method("session.choose","Ask the user to select currently open session targets.",false,"{\"title\":\"Select targets\",\"description\":\"Select servers to inspect\",\"multiple\":true,\"candidateIds\":[\"session-id\"]}","title"),
            method("session.chooseRoles","Ask the user to assign sessions to named roles.",false,"{\"title\":\"Select source and target\",\"description\":\"Choose open connections\",\"roles\":[{\"key\":\"source\",\"label\":\"Source\",\"required\":true},{\"key\":\"target\",\"label\":\"Target\",\"required\":true}]}","title","roles"),
            method("session.reveal","Reveal an explicit workspace session without changing operation targets.",true,"{\"sessionId\":\"session-id\",\"terminalId\":\"optional-terminal-id\",\"focus\":true}","sessionId"),
            method("session.close","Close a workspace session and release its resources.",true,"{\"sessionId\":\"session-id\"}","sessionId"),
            method("session.reconnect","Reconnect through the saved connection and return new identifiers.",true,"{\"sessionId\":\"session-id\",\"focus\":false}","sessionId"),
            method("terminal.list","List terminal instances and queue status for one session.",true,"{\"sessionId\":\"session-id\"}","sessionId"),
            method("terminal.events","Read bounded terminal input, output and command events by sequence.",true,"{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"afterSequence\":0,\"limit\":128}","sessionId"),
            method("terminal.enqueue","Queue explicit terminal input. awaitCommand requires shell tracking.",true,"{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"mode\":\"BATCH\",\"commands\":[\"pwd\",\"uname -a\"],\"intervalMs\":100,\"awaitCommand\":true,\"timeoutSeconds\":60}","sessionId","commands"),
            method("operation.get","Read a queued terminal or file operation.",false,"{\"operationId\":\"operation-id\"}","operationId"),
            method("operation.await","Wait for an operation's terminal state.",false,"{\"operationId\":\"operation-id\",\"timeoutSeconds\":60}","operationId"),
            method("operation.cancel","Cancel queued work. Delivered input may still execute remotely.",false,"{\"operationId\":\"operation-id\"}","operationId"),
            method("workspace.events","Read session, operation and job lifecycle events.",false,"{\"afterSequence\":0,\"limit\":128}"),
            method("job.create","Create explicitly targeted steps spanning sessions.",false,"{\"title\":\"Inspect servers\",\"steps\":[{\"stepId\":\"inspect\",\"title\":\"Inspect source\",\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\"}]}","title","steps"),
            method("job.get","Read one multi-session job.",false,"{\"jobId\":\"job-id\"}","jobId"),
            method("job.list","List retained jobs for this app run.",false,"{}"),
            method("job.updateStep","Report progress or completion of a caller-managed step.",false,"{\"jobId\":\"job-id\",\"stepId\":\"step-id\",\"state\":\"RUNNING\",\"progress\":0.5,\"message\":\"Processing\"}","jobId","stepId","state"),
            method("job.cancel","Cancel pending operations associated with a job.",false,"{\"jobId\":\"job-id\"}","jobId"),
            method("file.upload","Upload a local file to the named SSH session and return an operation ID.",true,"{\"sessionId\":\"session-id\",\"localPath\":\"/local/file\",\"remotePath\":\"/remote/file\"}","sessionId","localPath","remotePath"),
            method("file.download","Download from a named SSH session and return an operation ID.",true,"{\"sessionId\":\"session-id\",\"remotePath\":\"/remote/file\",\"localPath\":\"/local/file\"}","sessionId","remotePath","localPath"),
            method("file.preview","Open remote file preview in the associated workspace.",true,"{\"sessionId\":\"session-id\",\"path\":\"/remote/file\"}","sessionId","path")
        );
    }
    public static List<ProgramApiDefinition> workbenchDefinitions() {
        return List.of(
            method("workbench.sessions", "List all workspace session metadata.", false, "{}"),
            method("workbench.selectSession", "Reveal a named session.", true, "{\"sessionId\":\"session-id\"}", "sessionId"),
            method("terminal.snapshot", "Read bounded terminal screen, history and selection.", true, "{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"historyLines\":1000,\"maxCharacters\":262144}", "sessionId"),
            method("terminal.paste", "Queue text paste.", true, "{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"text\":\"hello\"}", "sessionId", "text"),
            method("terminal.submit", "Queue a single command followed by Enter.", true, "{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"command\":\"pwd\"}", "sessionId", "command"),
            method("terminal.key", "Queue a terminal control key.", true, "{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"key\":\"ENTER\"}", "sessionId", "key"),
            method("terminal.batch", "Queue commands with an interval.", true, "{\"sessionId\":\"session-id\",\"terminalId\":\"terminal-id\",\"commands\":[\"pwd\"],\"intervalMs\":100}", "sessionId", "commands"),
            method("file.list", "List remote directory entries.", true, "{\"sessionId\":\"session-id\",\"path\":\"/remote\"}", "sessionId", "path"),
            method("file.stat", "Read remote file metadata.", true, "{\"sessionId\":\"session-id\",\"path\":\"/remote/file\"}", "sessionId", "path"),
            method("file.readRange", "Read a bounded remote byte range as Base64.", true, "{\"sessionId\":\"session-id\",\"path\":\"/remote/file\",\"offset\":0,\"length\":65536}", "sessionId", "path"),
            method("file.rename", "Rename a remote path.", true, "{\"sessionId\":\"session-id\",\"source\":\"/remote/old\",\"target\":\"/remote/new\"}", "sessionId", "source", "target"),
            method("file.mkdir", "Create a remote directory.", true, "{\"sessionId\":\"session-id\",\"path\":\"/remote/new\",\"recursive\":true}", "sessionId", "path"),
            method("file.navigate", "Navigate a session file browser.", true, "{\"sessionId\":\"session-id\",\"remote\":true,\"directory\":\"/remote\",\"selectedPaths\":[\"/remote/file\"]}", "sessionId", "directory"),
            method("task.list", "List host plugin background tasks.", false, "{}"),
            method("task.cancel", "Cancel a host plugin background task.", false, "{\"taskId\":\"task-id\"}", "taskId")
        );
    }
    private static ProgramApiDefinition method(String name,String description,boolean session,String example,String... required) {
        JsonObject properties=new JsonObject();
        JsonParser.parseString(example).getAsJsonObject().entrySet().forEach(entry -> properties.add(entry.getKey(),schema(entry.getValue())));
        for(String field:List.of("jobId","stepId")) if(name.equals("terminal.enqueue") || name.equals("file.upload") || name.equals("file.download")) {
            JsonObject value=new JsonObject(); value.addProperty("type","string"); properties.add(field,value);
        }
        JsonObject input=new JsonObject(); input.addProperty("type","object"); input.add("properties",properties);
        JsonArray fields=new JsonArray(); for(String field:required) fields.add(field); input.add("required",fields);
        return new ProgramApiDefinition(name,description,session,input,"operation-specific JSON result",example);
    }
    private static JsonObject schema(JsonElement value) {
        JsonObject schema=new JsonObject();
        if(value.isJsonObject()) {
            schema.addProperty("type","object"); JsonObject properties=new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> properties.add(entry.getKey(),schema(entry.getValue()))); schema.add("properties",properties);
        } else if(value.isJsonArray()) {
            schema.addProperty("type","array"); schema.add("items",value.getAsJsonArray().isEmpty()?new JsonObject():schema(value.getAsJsonArray().get(0)));
        } else {
            var primitive=value.getAsJsonPrimitive(); schema.addProperty("type",primitive.isBoolean()?"boolean":primitive.isNumber()?"number":"string");
        }
        return schema;
    }
}
