package com.jlshell.app.api;

import com.google.gson.*;
import com.jlshell.program.api.ProgramWorkspaceService;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** JSON translation only. Java SDK and RPC share the same workspace coordinator. */
final class WorkspaceRpcMethods {
    private static final Gson GSON=new Gson();
    private WorkspaceRpcMethods() {}
    static CompletableFuture<JsonElement> invoke(ProgramWorkspaceService service,String method,JsonElement parameters) {
        try {
            JsonObject args=parameters!=null && parameters.isJsonObject()?parameters.getAsJsonObject():new JsonObject();
            return switch(method) {
                case "agent.context" -> json(service.context(new ContextRequest(bool(args,"includeTerminalSnapshot",false),integer(args,"historyLines",100),integer(args,"maxCharacters",65536))));
                case "file.readVersioned" -> service.files(required(args,"sessionId")).readVersioned(required(args,"path")).thenApply(content -> {
                    JsonObject value=new JsonObject(); value.addProperty("path",content.path()); value.addProperty("sha256",content.sha256());
                    value.addProperty("contentBase64",Base64.getEncoder().encodeToString(content.content())); return value;
                });
                case "file.write" -> json(service.files(required(args,"sessionId")).write(new com.jlshell.program.api.ProgramFileService.WriteRequest(
                        required(args,"path"),Base64.getDecoder().decode(Objects.requireNonNull(optional(args,"contentBase64"),"contentBase64 is required")),optional(args,"expectedSha256"),bool(args,"createOnly",false),bool(args,"backup",true))));
                case "file.applyPatch" -> json(service.files(required(args,"sessionId")).applyPatch(new com.jlshell.program.api.ProgramFileService.PatchRequest(
                        required(args,"path"),optional(args,"expectedSha256"),array(args,"edits").asList().stream().map(value -> {
                            JsonObject edit=value.getAsJsonObject(); return new com.jlshell.program.api.ProgramFileService.TextEdit(Objects.requireNonNull(optional(edit,"oldText"),"oldText is required"),Objects.requireNonNull(optional(edit,"newText"),"newText is required"));
                        }).toList(),bool(args,"backup",true))));
                case "process.start" -> json(service.processes().start(new com.jlshell.program.api.ProgramProcessService.Request(required(args,"sessionId"),strings(args,"argv"),
                        optional(args,"directory"),stringMap(args,"environment"),integer(args,"timeoutSeconds",60),integer(args,"maxOutputCharacters",262144))));
                case "process.get" -> value(service.processes().get(required(args,"processId")));
                case "process.await" -> json(service.processes().await(required(args,"processId"),integer(args,"timeoutSeconds",60)));
                case "process.events" -> value(service.processes().events(required(args,"processId"),longNumber(args,"afterSequence",0),integer(args,"limit",128)));
                case "process.cancel" -> value(service.processes().cancel(required(args,"processId")));
                case "job.checkpoint.get" -> checkpointJson(service.checkpoint(required(args,"jobId")));
                case "job.checkpoint.save" -> checkpointJson(service.checkpoint(required(args,"jobId"),longNumber(args,"expectedRevision",0),Objects.requireNonNull(args.get("data"),"data is required").toString()));
                case "job.resume" -> value(service.resumeJob(required(args,"jobId"),array(args,"targets").asList().stream().map(value -> {
                    JsonObject target=value.getAsJsonObject(); return new ResumeTarget(required(target,"stepId"),required(target,"sessionId"),optional(target,"terminalId"));
                }).toList()));
                case "connection.list" -> json(service.connections(new ConnectionQuery(optional(args,"text"),optional(args,"projectId"),optional(args,"folderId"))));
                case "connection.get" -> json(service.connections(new ConnectionQuery(null,null,null)).thenApply(values -> values.stream()
                        .filter(value -> value.connectionId().equals(required(args,"connectionId"))).findFirst().orElseThrow(() -> new IllegalArgumentException("Connection not found"))));
                case "session.open" -> json(service.open(new OpenRequest(required(args,"connectionId"),enumeration(args,"presentation",Presentation.TAB,Presentation.class),enumeration(args,"reuse",Reuse.IF_CONNECTED,Reuse.class),bool(args,"focus",false))));
                case "session.get" -> json(service.get(required(args,"sessionId")));
                case "session.awaitReady" -> json(service.awaitReady(required(args,"sessionId"),enumeration(args,"ready",Ready.TERMINAL,Ready.class),integer(args,"timeoutSeconds",30)));
                case "session.choose" -> json(service.choose(new SelectionRequest(required(args,"title"),Objects.toString(optional(args,"description"),""),bool(args,"multiple",false),strings(args,"candidateIds"))));
                case "session.chooseRoles" -> json(service.chooseRoles(new RoleRequest(required(args,"title"),Objects.toString(optional(args,"description"),""),
                        array(args,"roles").asList().stream().map(value -> { var role=value.getAsJsonObject(); return new Role(required(role,"key"),required(role,"label"),bool(role,"required",true)); }).toList(),strings(args,"candidateIds"))));
                case "session.reveal" -> json(service.reveal(required(args,"sessionId"),optional(args,"terminalId"),bool(args,"focus",true)));
                case "session.close" -> json(service.closeSession(required(args,"sessionId")));
                case "session.reconnect" -> json(service.reconnect(required(args,"sessionId"),bool(args,"focus",false)));
                case "terminal.list" -> value(service.terminals(required(args,"sessionId")));
                case "terminal.events" -> value(service.terminalEvents(required(args,"sessionId"),optional(args,"terminalId"),longNumber(args,"afterSequence",0),integer(args,"limit",128)));
                case "terminal.enqueue" -> json(service.enqueue(new Input(required(args,"sessionId"),optional(args,"terminalId"),optional(args,"jobId"),optional(args,"stepId"),
                        Objects.toString(optional(args,"mode"),"SUBMIT"),strings(args,"commands"),integer(args,"intervalMs",100),bool(args,"awaitCommand",false),integer(args,"timeoutSeconds",60))));
                case "operation.get" -> value(service.operation(required(args,"operationId")));
                case "operation.await" -> json(service.awaitOperation(required(args,"operationId"),integer(args,"timeoutSeconds",60)));
                case "operation.cancel" -> value(service.cancelOperation(required(args,"operationId")));
                case "workspace.events" -> value(service.events(longNumber(args,"afterSequence",0),integer(args,"limit",128)));
                case "job.create" -> value(service.createJob(required(args,"title"),array(args,"steps").asList().stream().map(item -> {
                    var step=item.getAsJsonObject(); return new Step(required(step,"stepId"),required(step,"title"),required(step,"sessionId"),optional(step,"terminalId"),State.QUEUED,0,"");
                }).toList()));
                case "job.get" -> value(service.job(required(args,"jobId")));
                case "job.list" -> value(service.jobs());
                case "job.updateStep" -> value(service.updateStep(required(args,"jobId"),required(args,"stepId"),enumeration(args,"state",State.RUNNING,State.class),
                        args.has("progress")?args.get("progress").getAsDouble():0,Objects.toString(optional(args,"message"),"")));
                case "job.cancel" -> value(service.cancelJob(required(args,"jobId")));
                case "file.upload" -> json(service.files(required(args,"sessionId")).upload(required(args,"localPath"),required(args,"remotePath"),optional(args,"jobId"),optional(args,"stepId")));
                case "file.download" -> json(service.files(required(args,"sessionId")).download(required(args,"remotePath"),required(args,"localPath"),optional(args,"jobId"),optional(args,"stepId")));
                case "file.preview" -> json(service.files(required(args,"sessionId")).preview(required(args,"path")));
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown workspace RPC method"));
            };
        } catch(RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    private static CompletableFuture<JsonElement> checkpointJson(CompletableFuture<Checkpoint> future) {
        return future.thenApply(checkpoint -> {
            JsonObject result = new JsonObject();
            result.addProperty("jobId",checkpoint.jobId()); result.addProperty("revision",checkpoint.revision());
            result.add("data",JsonParser.parseString(checkpoint.dataJson()));
            result.addProperty("updatedAt",checkpoint.updatedAt()); result.addProperty("recovered",checkpoint.recovered());
            return result;
        });
    }
    private static CompletableFuture<JsonElement> json(CompletableFuture<?> future) { return future.thenApply(GSON::toJsonTree); }
    private static CompletableFuture<JsonElement> value(Object object) { return CompletableFuture.completedFuture(GSON.toJsonTree(object)); }
    private static String required(JsonObject args,String name) {
        String value=optional(args,name); if(value==null || value.isBlank()) throw new IllegalArgumentException(name+" is required"); return value;
    }
    private static String optional(JsonObject args,String name) {
        if(!args.has(name) || args.get(name).isJsonNull()) return null;
        if(!args.get(name).isJsonPrimitive() || !args.getAsJsonPrimitive(name).isString()) throw new IllegalArgumentException(name+" must be a string");
        return args.get(name).getAsString();
    }
    private static boolean bool(JsonObject args,String name,boolean fallback) {
        if(!args.has(name)) return fallback;
        if(!args.get(name).isJsonPrimitive() || !args.getAsJsonPrimitive(name).isBoolean()) throw new IllegalArgumentException(name+" must be boolean");
        return args.get(name).getAsBoolean();
    }
    private static int integer(JsonObject args,String name,int fallback) { return Math.toIntExact(longNumber(args,name,fallback)); }
    private static long longNumber(JsonObject args,String name,long fallback) {
        if(!args.has(name)) return fallback;
        return args.get(name).getAsBigDecimal().longValueExact();
    }
    private static Map<String,String> stringMap(JsonObject args,String name) {
        if (!args.has(name)) return Map.of();
        if (!args.get(name).isJsonObject()) throw new IllegalArgumentException(name+" must be an object");
        Map<String,String> result=new LinkedHashMap<>();
        for (String key : args.getAsJsonObject(name).keySet()) result.put(key,Objects.requireNonNull(optional(args.getAsJsonObject(name),key)));
        return result;
    }
    private static JsonArray array(JsonObject args,String name) {
        if(!args.has(name) || !args.get(name).isJsonArray()) throw new IllegalArgumentException(name+" must be an array");
        return args.getAsJsonArray(name);
    }
    private static List<String> strings(JsonObject args,String name) {
        if(!args.has(name)) return List.of();
        return array(args,name).asList().stream().map(value -> {
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name+" must contain strings");
            return value.getAsString();
        }).toList();
    }
    private static <E extends Enum<E>> E enumeration(JsonObject args,String name,E fallback,Class<E> type) {
        String value=optional(args,name); return value==null?fallback:Enum.valueOf(type,value.toUpperCase(Locale.ROOT));
    }
}
