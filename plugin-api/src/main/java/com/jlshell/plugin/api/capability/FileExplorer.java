package com.jlshell.plugin.api.capability;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.model.RemoteFile;

public interface FileExplorer {

    CompletableFuture<List<RemoteFile>> listDirectory(String path);

    CompletableFuture<byte[]> readFile(String path);

    CompletableFuture<Void> writeFile(String path, byte[] content);

    CompletableFuture<Void> deleteFile(String path);
}
