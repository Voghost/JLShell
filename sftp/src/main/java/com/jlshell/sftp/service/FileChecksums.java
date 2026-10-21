package com.jlshell.sftp.service;

import com.jlshell.sftp.exception.TransferCancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/** Streaming file-integrity checks; callers run filesystem work outside the UI thread. */
public final class FileChecksums {
    private FileChecksums() {}

    public static String localMd5(Path path, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        var before = Files.readAttributes(path, BasicFileAttributes.class);
        if (!before.isRegularFile()) throw new IOException("MD5 requires a regular file");
        String digest;
        try (var input = Files.newInputStream(path)) { digest = md5(input, before.size(), cancelled); }
        var after = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("File changed while calculating MD5");
        }
        return digest;
    }

    public static String md5(InputStream input, long expectedBytes, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        while (true) {
            checkCancelled(cancelled);
            int read = input.read(buffer);
            if (read < 0) break;
            if (read == 0) continue;
            digest.update(buffer, 0, read);
            bytes += read;
            if (bytes > expectedBytes) throw new IOException("File changed while calculating MD5");
        }
        checkCancelled(cancelled);
        if (bytes != expectedBytes) throw new IOException("File changed while calculating MD5");
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean())
            throw new TransferCancelledException();
    }
}
