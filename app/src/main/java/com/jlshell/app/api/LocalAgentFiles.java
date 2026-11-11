package com.jlshell.app.api;

import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.model.RemoteFile;
import com.jlshell.program.api.ProgramFileService.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** File access associated with an explicitly selected local-shell session. */
public final class LocalAgentFiles implements FileExplorer {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0,64).mapToObj(index -> new Object()).toArray();
    private final Executor executor;
    public LocalAgentFiles(Executor executor) { this.executor = executor; }
    private <T> CompletableFuture<T> run(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try { return work.call(); } catch (Exception error) { throw new CompletionException(error); }
        },executor);
    }
    private RemoteFile describe(Path path) throws IOException {
        var attributes = Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return new RemoteFile(path.getFileName().toString(),path.toAbsolutePath().toString(),attributes.size(),attributes.isDirectory(),"",attributes.lastModifiedTime().toInstant());
    }
    public CompletableFuture<List<RemoteFile>> listDirectory(String directory) {
        return run(() -> {
            try (var paths = Files.list(Path.of(directory))) {
                List<RemoteFile> values = new ArrayList<>();
                for (Path path : paths.toList()) values.add(describe(path));
                return List.copyOf(values);
            }
        });
    }
    public CompletableFuture<RemoteFile> stat(String path) { return run(() -> describe(Path.of(path))); }
    public CompletableFuture<byte[]> readFile(String path) { return readVersioned(path).thenApply(Content::content); }
    public CompletableFuture<Void> writeFile(String path,byte[] bytes) { return writeAtomic(new WriteRequest(path,bytes,null,false,false)).thenApply(ignored -> null); }
    public CompletableFuture<Void> deleteFile(String path) { return run(() -> { Files.delete(Path.of(path)); return null; }); }
    public CompletableFuture<Void> rename(String source,String target) { return run(() -> { Files.move(Path.of(source),Path.of(target)); return null; }); }
    public CompletableFuture<Void> createDirectory(String path,boolean recursive) {
        return run(() -> { if (recursive) Files.createDirectories(Path.of(path)); else Files.createDirectory(Path.of(path)); return null; });
    }
    public CompletableFuture<byte[]> readRange(String path,long offset,int length) {
        if (offset < 0 || length < 0 || length > MAX_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid read range"));
        return run(() -> {
            try (var channel = Files.newByteChannel(Path.of(path),StandardOpenOption.READ)) {
                channel.position(offset);
                var buffer = java.nio.ByteBuffer.allocate(length);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
                return Arrays.copyOf(buffer.array(),buffer.position());
            }
        });
    }
    public CompletableFuture<Content> readVersioned(String path) { return run(() -> read(Path.of(path))); }
    private static Content read(Path path) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        if (Files.size(path) > MAX_BYTES) throw new IOException("File exceeds 4 MiB edit limit");
        byte[] content;
        try (InputStream input = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { content = input.readNBytes(MAX_BYTES + 1); }
        if (content.length > MAX_BYTES) throw new IOException("File exceeds 4 MiB edit limit");
        return new Content(path.toAbsolutePath().toString(),content,sha(content));
    }
    public CompletableFuture<WriteResult> writeAtomic(WriteRequest request) {
        return run(() -> {
            byte[] bytes = request.content();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Content exceeds 4 MiB edit limit");
            if (request.expectedSha256() != null && !request.expectedSha256().matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
            Path supplied = Path.of(request.path()).toAbsolutePath().normalize();
            Path target = supplied.getParent().toRealPath().resolve(supplied.getFileName());
            synchronized (LOCKS[Math.floorMod(target.hashCode(),LOCKS.length)]) {
                boolean exists = Files.exists(target,LinkOption.NOFOLLOW_LINKS);
                if (request.createOnly() && exists) throw new FileAlreadyExistsException(target.toString());
                Content previous = exists ? read(target) : null;
                if (request.expectedSha256() != null && (previous == null || !request.expectedSha256().equalsIgnoreCase(previous.sha256()))) throw new IOException("File revision conflict");
                Path temporary = Files.createTempFile(target.getParent(),".jlshell-tmp-","");
                Path backup = null;
                try {
                    Files.write(temporary,bytes);
                    if (exists && Files.getFileStore(target).supportsFileAttributeView("posix")) {
                        var attributes = Files.readAttributes(target,java.nio.file.attribute.PosixFileAttributes.class);
                        Files.setPosixFilePermissions(temporary,attributes.permissions());
                        Files.getFileAttributeView(temporary,java.nio.file.attribute.PosixFileAttributeView.class).setGroup(attributes.group());
                        if (!Files.getOwner(temporary).equals(attributes.owner())) Files.setOwner(temporary,attributes.owner());
                    }
                    if (exists && request.backup()) {
                        backup = target.resolveSibling(target.getFileName()+".jlshell-backup-"+UUID.randomUUID());
                        Files.copy(target,backup,StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    Content current = Files.exists(target,LinkOption.NOFOLLOW_LINKS) ? read(target) : null;
                    if (!Objects.equals(previous == null ? null : previous.sha256(), current == null ? null : current.sha256())) throw new IOException("File revision conflict before commit");
                    if (request.createOnly()) Files.createLink(target,temporary);
                    else Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    return new WriteResult(target.toString(),previous == null ? null : previous.sha256(),sha(bytes),backup == null ? null : backup.toString(),bytes.length);
                } finally { Files.deleteIfExists(temporary); }
            }
        });
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
