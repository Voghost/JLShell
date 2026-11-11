package com.jlshell.program.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Authenticated workbench operations. Terminal operations require an explicit sessionId. */
@FunctionalInterface
public interface ProgramWorkbenchService {
    java.util.List<String> OPERATIONS = java.util.List.of("workbench.sessions","workbench.selectSession",
            "terminal.snapshot","terminal.paste","terminal.submit","terminal.key","terminal.batch",
            "file.list","file.stat","file.readRange","file.rename","file.mkdir","file.navigate","task.list","task.cancel");
    CompletableFuture<JsonElement> invoke(String operation, JsonObject arguments);
    static ProgramWorkbenchService unavailable() {
        return (operation,args) -> CompletableFuture.failedFuture(new UnsupportedOperationException("Workbench unavailable"));
    }
}
