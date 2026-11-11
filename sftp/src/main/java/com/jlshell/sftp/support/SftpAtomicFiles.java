package com.jlshell.sftp.support;

import com.jlshell.sftp.service.SftpService;
import net.schmizz.sshj.sftp.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Same-directory staged writes. A server must support atomic overwrite before existing files are replaced. */
final class SftpAtomicFiles {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 64).mapToObj(index -> new Object()).toArray();
    private SftpAtomicFiles() {}

    static byte[] read(SFTPClient client, String path) throws IOException {
        FileAttributes before = client.lstat(path);
        if (before.getType() != FileMode.Type.REGULAR) throw new IOException("Expected a regular file");
        if (before.getSize() > MAX_BYTES) throw new IOException("File exceeds 4 MiB edit limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (RemoteFile file = client.open(path, EnumSet.of(OpenMode.READ))) {
            byte[] buffer = new byte[32768];
            long offset = 0;
            int count;
            while ((count = file.read(offset, buffer, 0, buffer.length)) >= 0) {
                if (count == 0) continue;
                offset += count;
                if (offset > MAX_BYTES) throw new IOException("File exceeds 4 MiB edit limit");
                output.write(buffer, 0, count);
            }
        }
        FileAttributes after = client.lstat(path);
        if (after.getSize() != before.getSize() || after.getMtime() != before.getMtime())
            throw new IOException("File changed during read");
        return output.toByteArray();
    }
    static SftpService.WriteResult write(SFTPClient client, String server, String path, byte[] bytes,
            String expectedSha, boolean createOnly, boolean backup) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Content exceeds 4 MiB edit limit");
        if (path == null || path.isBlank() || path.endsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid file path");
        if (expectedSha != null && !expectedSha.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256 must have 64 hex characters");
        int slash = path.lastIndexOf('/');
        String parent = client.canonicalize(slash < 0 ? "." : slash == 0 ? "/" : path.substring(0, slash));
        String target = (parent.endsWith("/") ? parent : parent + "/") + path.substring(slash + 1);
        Object lock = LOCKS[Math.floorMod((server + target).hashCode(), LOCKS.length)];
        synchronized (lock) {
            FileAttributes original = existing(client, target);
            if (createOnly && original != null) throw new IOException("File already exists");
            byte[] previous = original == null ? null : read(client, target);
            String previousSha = previous == null ? null : sha256(previous);
            if (expectedSha != null && !expectedSha.equalsIgnoreCase(previousSha)) throw new IOException("File revision conflict");
            String temporary = target + ".jlshell-tmp-" + UUID.randomUUID();
            String backupPath = backup && previous != null ? target + ".jlshell-backup-" + UUID.randomUUID() : null;
            try {
                stage(client, temporary, bytes, original);
                if (backupPath != null) stage(client, backupPath, previous, original);
                // Check again after staging. Remote programs can still race an SFTP rename.
                FileAttributes current = existing(client, target);
                String currentSha = current == null ? null : sha256(read(client, target));
                if (!Objects.equals(previousSha, currentSha)) throw new IOException("File revision conflict before commit");
                if (original == null) client.rename(temporary, target);
                else client.rename(temporary, target, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE));
                return new SftpService.WriteResult(target, previousSha, sha256(bytes), backupPath, bytes.length);
            } finally {
                try { client.rm(temporary); } catch (IOException ignored) { }
            }
        }
    }
    private static FileAttributes existing(SFTPClient client, String path) throws IOException {
        try { return client.lstat(path); }
        catch (SFTPException error) {
            if (error.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) return null;
            throw error;
        }
    }
    private static void stage(SFTPClient client, String path, byte[] bytes, FileAttributes original) throws IOException {
        FileAttributes attributes = original == null ? new FileAttributes.Builder().withPermissions(0600).build()
                : new FileAttributes.Builder().withPermissions(original.getPermissions()).withUIDGID(original.getUID(),original.getGID()).build();
        try (RemoteFile file = client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), attributes)) {
            for (int offset = 0; offset < bytes.length; offset += 32768) file.write(offset, bytes, offset, Math.min(32768, bytes.length - offset));
        }
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
