package com.jlshell.program.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** File operations bound to a single SSH session. Paths remain remote unless explicitly named local. */
public interface ProgramFileService {
    record File(String name,String path,long size,boolean directory,String permissions,String lastModified) {}
    CompletableFuture<List<File>> list(String path);
    CompletableFuture<File> stat(String path);
    CompletableFuture<byte[]> readRange(String path,long offset,int length);
    CompletableFuture<Void> rename(String source,String target);
    CompletableFuture<Void> mkdir(String path,boolean recursive);
    CompletableFuture<ProgramWorkspaceService.Operation> upload(String local,String remote,String jobId,String stepId);
    CompletableFuture<ProgramWorkspaceService.Operation> download(String remote,String local,String jobId,String stepId);
    CompletableFuture<Void> navigate(String directory,List<String> selectedPaths);
    CompletableFuture<Void> preview(String path);
}
