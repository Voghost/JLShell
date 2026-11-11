package com.jlshell.app.api;

import com.google.gson.*;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.loader.HostExtensionRegistry;
import com.jlshell.program.api.ProgramWorkbenchService;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class DefaultProgramWorkbenchService implements ProgramWorkbenchService {
    private final com.jlshell.program.api.ProgramWorkspaceService workspace;
    private final Gson gson = new Gson();
    private final java.util.function.Function<String,com.jlshell.plugin.api.capability.FileExplorer> files;
    public DefaultProgramWorkbenchService(java.util.function.Function<String,com.jlshell.plugin.api.capability.FileExplorer> files) { this(files,null); }
    public DefaultProgramWorkbenchService(java.util.function.Function<String,com.jlshell.plugin.api.capability.FileExplorer> files,com.jlshell.program.api.ProgramWorkspaceService workspace) { this.files=files; this.workspace=workspace; }

    public CompletableFuture<JsonElement> invoke(String operation,JsonObject args) {
        try {
            String id=args.has("sessionId") ? args.get("sessionId").getAsString() : null;
            if(operation.equals("workbench.sessions")) return workspace==null?result(HostExtensionRegistry.sessions()):workspace.sessions().thenApply(gson::toJsonTree);
            if(operation.equals("task.list")) {
                JsonArray values=new JsonArray();
                for(var task:HostExtensionRegistry.tasks()) {
                    JsonObject item=new JsonObject(); item.addProperty("id",task.id()); item.addProperty("pluginId",task.pluginId());
                    item.addProperty("title",task.title()); item.addProperty("progress",task.progress()); item.addProperty("message",task.message()); values.add(item);
                }
                return CompletableFuture.completedFuture(values);
            }
            if(operation.equals("task.cancel")) {
                String taskId=text(args,"taskId");
                HostExtensionRegistry.tasks().stream().filter(task -> task.id().equals(taskId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Task not found")).cancel().run();
                return CompletableFuture.completedFuture(JsonNull.INSTANCE);
            }
            if(id==null || id.isBlank()) throw new IllegalArgumentException("sessionId required");
            if(operation.equals("workbench.selectSession")) return (workspace==null?HostExtensionRegistry.selectSession(id):workspace.reveal(id,null,true)).thenApply(ignored -> JsonNull.INSTANCE);
            if(operation.equals("file.navigate")) return (workspace==null?CompletableFuture.<Void>completedFuture(null):HostExtensionRegistry.workspaceUi().ensureFiles(id)).thenCompose(ready -> HostExtensionRegistry.navigate(id,
                    args.has("remote") && args.get("remote").getAsBoolean(),text(args,"directory"),
                    args.has("selectedPaths") ? args.getAsJsonArray("selectedPaths").asList().stream().map(JsonElement::getAsString).toList() : java.util.List.of())
                    .thenApply(ignored -> JsonNull.INSTANCE));
            if(operation.startsWith("file.")) {
                var file=files.apply(id);
                return switch(operation) {
                    case "file.list" -> file.listDirectory(text(args,"path")).thenApply(list -> { JsonArray values=new JsonArray(); list.forEach(item -> values.add(fileJson(item))); return values; });
                    case "file.stat" -> file.stat(text(args,"path")).thenApply(DefaultProgramWorkbenchService::fileJson);
                    case "file.readRange" -> file.readRange(text(args,"path"),args.has("offset")?args.get("offset").getAsLong():0,number(args,"length",65536))
                            .thenApply(bytes -> new JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes)));
                    case "file.rename" -> file.rename(text(args,"source"),text(args,"target")).thenApply(ignored -> JsonNull.INSTANCE);
                    case "file.mkdir" -> file.createDirectory(text(args,"path"),args.has("recursive") && args.get("recursive").getAsBoolean()).thenApply(ignored -> JsonNull.INSTANCE);
                    default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown file operation"));
                };
            }
            String terminalId=args.has("terminalId")?args.get("terminalId").getAsString():null;
            TerminalAccess terminal=HostExtensionRegistry.resolveTerminal(id,terminalId);
            if(workspace!=null && java.util.Set.of("terminal.paste","terminal.submit","terminal.key","terminal.batch").contains(operation)) {
                String mode=switch(operation) { case "terminal.paste" -> "PASTE"; case "terminal.key" -> "KEY"; case "terminal.batch" -> "BATCH"; default -> "SUBMIT"; };
                java.util.List<String> commands=operation.equals("terminal.batch")?args.getAsJsonArray("commands").asList().stream().map(JsonElement::getAsString).toList()
                        :java.util.List.of(text(args,operation.equals("terminal.paste")?"text":operation.equals("terminal.key")?"key":"command"));
                var input=new com.jlshell.program.api.ProgramWorkspaceService.Input(id,terminal.terminalId(),null,null,mode,commands,number(args,"intervalMs",100),false,60);
                return workspace.enqueue(input).thenCompose(queued -> workspace.awaitOperation(queued.operationId(),60)).thenApply(finished -> {
                    if(finished.state()!=com.jlshell.program.api.ProgramWorkspaceService.State.SUCCEEDED) throw new IllegalStateException(finished.message());
                    return operation.equals("terminal.batch")?gson.toJsonTree(commands.size()):JsonNull.INSTANCE;
                });
            }
            return switch(operation) {
                case "terminal.snapshot" -> terminal.snapshot(number(args,"historyLines",1000),number(args,"maxCharacters",262144)).thenApply(gson::toJsonTree);
                case "terminal.paste" -> terminal.paste(text(args,"text")).thenApply(ignored -> JsonNull.INSTANCE);
                case "terminal.submit" -> terminal.submit(text(args,"command")).thenApply(ignored -> JsonNull.INSTANCE);
                case "terminal.key" -> terminal.sendKey(TerminalAccess.Key.valueOf(text(args,"key"))).thenApply(ignored -> JsonNull.INSTANCE);
                case "terminal.batch" -> terminal.submitBatch(args.getAsJsonArray("commands").asList().stream().map(JsonElement::getAsString).toList(),
                        Duration.ofMillis(number(args,"intervalMs",100)),null).thenApply(gson::toJsonTree);
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown workbench operation"));
            };
        } catch(RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private static JsonElement fileJson(com.jlshell.plugin.api.model.RemoteFile file) {
        JsonObject result=new JsonObject(); result.addProperty("name",file.name()); result.addProperty("path",file.path());
        result.addProperty("size",file.size()); result.addProperty("directory",file.isDirectory());
        result.addProperty("permissions",file.permissions());
        result.addProperty("lastModified",file.lastModified()==null ? null : file.lastModified().toString());
        return result;
    }
    private CompletableFuture<JsonElement> result(Object value) { return CompletableFuture.completedFuture(gson.toJsonTree(value)); }
    private static String text(JsonObject args,String key) {
        if(!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key+" must be a string");
        return args.get(key).getAsString();
    }
    private static int number(JsonObject args,String key,int fallback) { return args.has(key) ? args.get(key).getAsInt() : fallback; }
}
