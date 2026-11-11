package com.jlshell.plugin.loader;

import com.jlshell.program.api.ProgramWorkspaceService.*;
import java.util.concurrent.CompletableFuture;

/** Desktop controller bridge. Implementations marshal UI mutations to the EDT. */
public interface WorkspaceUi {
    CompletableFuture<String> open(OpenRequest request);
    CompletableFuture<Selection> choose(SelectionRequest request);
    CompletableFuture<RoleSelection> chooseRoles(RoleRequest request);
    CompletableFuture<Void> reveal(String sessionId,String terminalId,boolean focus);
    CompletableFuture<Void> closeSession(String sessionId);
    CompletableFuture<Void> ensureFiles(String sessionId);
}
