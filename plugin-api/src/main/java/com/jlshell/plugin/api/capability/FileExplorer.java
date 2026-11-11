package com.jlshell.plugin.api.capability;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.model.RemoteFile;

public interface FileExplorer {

    CompletableFuture<List<RemoteFile>> listDirectory(String path);

    CompletableFuture<byte[]> readFile(String path);

    CompletableFuture<Void> writeFile(String path, byte[] content);

    CompletableFuture<Void> deleteFile(String path);

    default CompletableFuture<RemoteFile> stat(String path) { return unsupported(); }
    default CompletableFuture<Void> rename(String source, String target) { return unsupported(); }
    default CompletableFuture<Void> createDirectory(String path, boolean recursive) { return unsupported(); }
    /** Bounded read. Implementations reject a range beyond their supported maximum. */
    default CompletableFuture<byte[]> readRange(String path, long offset, int length) { return unsupported(); }
    record Progress(long transferred, long total) {}
    /** Cancellation is cooperative and does not undo transferred bytes. */
    default CompletableFuture<Void> upload(java.nio.file.Path local, String remote,
            java.util.function.Consumer<Progress> progress, java.util.function.BooleanSupplier cancelled) { return unsupported(); }
    default CompletableFuture<Void> download(String remote, java.nio.file.Path local,
            java.util.function.Consumer<Progress> progress, java.util.function.BooleanSupplier cancelled) { return unsupported(); }
    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("File operation unavailable"));
    }

}
