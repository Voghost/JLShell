package com.jlshell.program.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** File operations bound to a single SSH session. Paths remain remote unless explicitly named local. */
public interface ProgramFileService {
    record File(String name,String path,long size,boolean directory,String permissions,String lastModified) {}
    record Content(String path, byte[] content, String sha256) {
        public Content { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    record WriteRequest(String path, byte[] content, String expectedSha256, boolean createOnly, boolean backup) {
        public WriteRequest { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    record WriteResult(String path, String previousSha256, String sha256, String backupPath, long bytesWritten) {}
    /** Each oldText must occur exactly once. Edits apply in order to UTF-8 content. */
    record TextEdit(String oldText, String newText) {}
    record PatchRequest(String path, String expectedSha256, List<TextEdit> edits, boolean backup) {
        public PatchRequest { edits = List.copyOf(edits); }
    }
    default CompletableFuture<Content> readVersioned(String path) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Versioned reads unavailable")); }
    default CompletableFuture<WriteResult> write(WriteRequest request) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Atomic writes unavailable")); }
    default CompletableFuture<WriteResult> applyPatch(PatchRequest request) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Patches unavailable")); }
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
