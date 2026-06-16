package com.jlshell.sftp.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.exception.SftpOperationException;
import com.jlshell.sftp.model.RemoteDirectoryListing;
import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.sftp.model.TransferDirection;
import com.jlshell.sftp.model.TransferProgress;
import com.jlshell.sftp.model.TransferRequest;
import com.jlshell.sftp.model.TransferResumeMode;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.service.TransferProgressListener;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 SSHJ 的 SFTP 服务实现。
 * 上传下载采用并发分片传输，每个分片使用独立的 SFTP 通道以突破单通道性能瓶颈。
 */
public class SshjSftpService implements SftpService {

    private static final Logger log = LoggerFactory.getLogger(SshjSftpService.class);

    /** File size threshold for parallel transfer (8 MB). */
    private static final long PARALLEL_THRESHOLD = 8 * 1024 * 1024;
    /** Number of concurrent SFTP channels for large files. */
    private static final int PARALLELISM = 4;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 200;

    private final ExecutorService executorService;

    public SshjSftpService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public CompletableFuture<RemoteDirectoryListing> listDirectory(SshSession sshSession, String directoryPath) {
        return CompletableFuture.supplyAsync(() -> {
            try (SFTPClient sftpClient = newSftpClient(sshSession)) {
                String canonicalPath = sftpClient.canonicalize(directoryPath);
                List<RemoteFileEntry> entries = sftpClient.ls(canonicalPath)
                        .stream()
                        .filter(resource -> !isCurrentOrParentDirectory(resource.getName()))
                        .map(RemoteFileMapper::fromRemoteResourceInfo)
                        .sorted(Comparator
                                .comparing(RemoteFileEntry::isDirectory)
                                .reversed()
                                .thenComparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                return new RemoteDirectoryListing(canonicalPath, entries);
            } catch (IOException exception) {
                throw new SftpOperationException("Failed to list remote directory: " + directoryPath, exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<RemoteFileEntry> stat(SshSession sshSession, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try (SFTPClient sftpClient = newSftpClient(sshSession)) {
                String canonicalPath = sftpClient.canonicalize(path);
                return RemoteFileMapper.fromAttributes(canonicalPath, fileName(canonicalPath), sftpClient.stat(canonicalPath));
            } catch (IOException exception) {
                throw new SftpOperationException("Failed to stat remote path: " + path, exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> upload(
            SshSession sshSession,
            TransferRequest request,
            TransferProgressListener listener
    ) {
        TransferProgressListener pl = listener == null ? TransferProgressListener.NO_OP : listener;
        return CompletableFuture.runAsync(() -> doUpload(sshSession, request, pl), executorService);
    }

    @Override
    public CompletableFuture<Void> download(
            SshSession sshSession,
            TransferRequest request,
            TransferProgressListener listener
    ) {
        TransferProgressListener pl = listener == null ? TransferProgressListener.NO_OP : listener;
        return CompletableFuture.runAsync(() -> doDownload(sshSession, request, pl), executorService);
    }

    @Override
    public CompletableFuture<Void> rename(SshSession sshSession, String sourcePath, String targetPath) {
        return CompletableFuture.runAsync(() -> {
            try (SFTPClient sftpClient = newSftpClient(sshSession)) {
                sftpClient.rename(sourcePath, targetPath);
            } catch (IOException exception) {
                throw new SftpOperationException("Failed to rename remote path from " + sourcePath + " to " + targetPath, exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> delete(SshSession sshSession, String remotePath, boolean recursive) {
        return CompletableFuture.runAsync(() -> {
            try (SFTPClient sftpClient = newSftpClient(sshSession)) {
                deletePath(sftpClient, remotePath, recursive);
            } catch (IOException exception) {
                throw new SftpOperationException("Failed to delete remote path: " + remotePath, exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> createDirectory(SshSession sshSession, String remotePath, boolean recursive) {
        return CompletableFuture.runAsync(() -> {
            try (SFTPClient sftpClient = newSftpClient(sshSession)) {
                if (recursive) {
                    sftpClient.mkdirs(remotePath);
                } else {
                    sftpClient.mkdir(remotePath);
                }
            } catch (IOException exception) {
                throw new SftpOperationException("Failed to create remote directory: " + remotePath, exception);
            }
        }, executorService);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    private void doUpload(SshSession sshSession, TransferRequest request, TransferProgressListener listener) {
        Path localPath = request.localPath();
        if (!Files.exists(localPath) || Files.isDirectory(localPath)) {
            throw new SftpOperationException("Local upload source must be an existing file: " + localPath);
        }

        try {
            long totalBytes = Files.size(localPath);
            listener.onStarted(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), request.remotePath(), 0, totalBytes));

            if (totalBytes >= PARALLEL_THRESHOLD) {
                parallelUpload(sshSession, localPath, request.remotePath(), totalBytes, listener);
            } else {
                sequentialUpload(sshSession, localPath, request.remotePath(), totalBytes, listener);
            }

            listener.onCompleted(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), request.remotePath(), totalBytes, totalBytes));
        } catch (Throwable t) {
            listener.onFailed(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), request.remotePath(), 0, 0), t);
            throw t instanceof SftpOperationException e ? e : new SftpOperationException("Upload failed", t);
        }
    }

    private void sequentialUpload(SshSession sshSession, Path localPath, String remotePath, long totalBytes, TransferProgressListener listener) throws IOException {
        try (SFTPClient sftpClient = newSftpClient(sshSession);
             InputStream in = new BufferedInputStream(Files.newInputStream(localPath), BUFFER_SIZE);
             RemoteFile remoteFile = sftpClient.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {

            byte[] buf = new byte[BUFFER_SIZE];
            long position = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buf)) >= 0) {
                if (read == 0) continue;
                if (listener.isCancelled()) throw new IOException("Transfer cancelled");
                remoteFile.write(position, buf, 0, read);
                position += read;
                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    listener.onProgress(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), remotePath, position, totalBytes));
                }
            }
            listener.onProgress(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), remotePath, position, totalBytes));
        }
    }

    private void parallelUpload(SshSession sshSession, Path localPath, String remotePath, long totalBytes, TransferProgressListener listener) throws IOException {
        int chunks = PARALLELISM;
        long chunkSize = (totalBytes + chunks - 1) / chunks;
        AtomicLong transferred = new AtomicLong(0);
        long lastReport = 0;

        try (SFTPClient sftp = newSftpClient(sshSession);
             RemoteFile rf = sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {

            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[chunks];
            for (int i = 0; i < chunks; i++) {
                final long start = i * chunkSize;
                final long end = Math.min(start + chunkSize, totalBytes);
                if (start >= totalBytes) {
                    futures[i] = CompletableFuture.completedFuture(null);
                    continue;
                }
                futures[i] = CompletableFuture.runAsync(() -> {
                    try (RandomAccessFile raf = new RandomAccessFile(localPath.toFile(), "r")) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        long pos = start;
                        raf.seek(start);
                        while (pos < end) {
                            if (listener.isCancelled()) throw new IOException("Transfer cancelled");
                            int toRead = (int) Math.min(buf.length, end - pos);
                            int read = raf.read(buf, 0, toRead);
                            if (read < 0) break;
                            rf.write(pos, buf, 0, read);
                            pos += read;
                            transferred.addAndGet(read);
                        }
                    } catch (IOException e) {
                        throw new SftpOperationException("Upload chunk failed", e);
                    }
                }, executorService);
            }

            while (!CompletableFuture.allOf(futures).isDone()) {
                try { Thread.sleep(PROGRESS_INTERVAL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (listener.isCancelled()) {
                    for (var f : futures) f.cancel(true);
                    throw new IOException("Transfer cancelled");
                }
                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    listener.onProgress(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), remotePath, transferred.get(), totalBytes));
                }
            }

            try { CompletableFuture.allOf(futures).join(); }
            catch (Exception e) { throw new IOException("Parallel upload failed", e.getCause() != null ? e.getCause() : e); }

            listener.onProgress(new TransferProgress(TransferDirection.UPLOAD, localPath.toString(), remotePath, totalBytes, totalBytes));
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private void doDownload(SshSession sshSession, TransferRequest request, TransferProgressListener listener) {
        Path localPath = request.localPath();
        try {
            long totalBytes;
            String canonicalRemotePath;
            try (SFTPClient sftp = newSftpClient(sshSession)) {
                canonicalRemotePath = sftp.canonicalize(request.remotePath());
                totalBytes = sftp.size(canonicalRemotePath);
            }

            if (localPath.toAbsolutePath().getParent() != null) {
                Files.createDirectories(localPath.toAbsolutePath().getParent());
            }

            listener.onStarted(new TransferProgress(TransferDirection.DOWNLOAD, canonicalRemotePath, localPath.toString(), 0, totalBytes));

            if (totalBytes >= PARALLEL_THRESHOLD) {
                parallelDownload(sshSession, canonicalRemotePath, localPath, totalBytes, listener);
            } else {
                sequentialDownload(sshSession, canonicalRemotePath, localPath, totalBytes, listener);
            }

            listener.onCompleted(new TransferProgress(TransferDirection.DOWNLOAD, canonicalRemotePath, localPath.toString(), totalBytes, totalBytes));
        } catch (Throwable t) {
            listener.onFailed(new TransferProgress(TransferDirection.DOWNLOAD, request.remotePath(), localPath.toString(), 0, 0), t);
            throw t instanceof SftpOperationException e ? e : new SftpOperationException("Download failed", t);
        }
    }

    private void sequentialDownload(SshSession sshSession, String remotePath, Path localPath, long totalBytes, TransferProgressListener listener) throws IOException {
        try (SFTPClient sftp = newSftpClient(sshSession);
             RemoteFile rf = sftp.open(remotePath, EnumSet.of(OpenMode.READ));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(localPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), BUFFER_SIZE)) {

            byte[] buf = new byte[BUFFER_SIZE];
            long position = 0;
            long lastReport = 0;
            while (position < totalBytes) {
                if (listener.isCancelled()) throw new IOException("Transfer cancelled");
                int read = rf.read(position, buf, 0, (int) Math.min(buf.length, totalBytes - position));
                if (read < 0) break;
                out.write(buf, 0, read);
                out.flush();
                position += read;
                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    listener.onProgress(new TransferProgress(TransferDirection.DOWNLOAD, remotePath, localPath.toString(), position, totalBytes));
                }
            }
            listener.onProgress(new TransferProgress(TransferDirection.DOWNLOAD, remotePath, localPath.toString(), position, totalBytes));
        }
    }

    private void parallelDownload(SshSession sshSession, String remotePath, Path localPath, long totalBytes, TransferProgressListener listener) throws IOException {
        int chunks = PARALLELISM;
        long chunkSize = (totalBytes + chunks - 1) / chunks;
        AtomicLong transferred = new AtomicLong(0);
        long lastReport = 0;

        // Pre-allocate local file
        try (RandomAccessFile preRaf = new RandomAccessFile(localPath.toFile(), "rw")) {
            preRaf.setLength(totalBytes);
        }

        try (SFTPClient sftp = newSftpClient(sshSession);
             RemoteFile rf = sftp.open(remotePath, EnumSet.of(OpenMode.READ))) {

            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[chunks];
            for (int i = 0; i < chunks; i++) {
                final long start = i * chunkSize;
                final long end = Math.min(start + chunkSize, totalBytes);
                if (start >= totalBytes) {
                    futures[i] = CompletableFuture.completedFuture(null);
                    continue;
                }
                futures[i] = CompletableFuture.runAsync(() -> {
                    try (RandomAccessFile raf = new RandomAccessFile(localPath.toFile(), "rw")) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        long pos = start;
                        raf.seek(start);
                        while (pos < end) {
                            if (listener.isCancelled()) throw new IOException("Transfer cancelled");
                            int toRead = (int) Math.min(buf.length, end - pos);
                            int read = rf.read(pos, buf, 0, toRead);
                            if (read < 0) break;
                            raf.write(buf, 0, read);
                            pos += read;
                            transferred.addAndGet(read);
                        }
                    } catch (IOException e) {
                        throw new SftpOperationException("Download chunk failed", e);
                    }
                }, executorService);
            }

            while (!CompletableFuture.allOf(futures).isDone()) {
                try { Thread.sleep(PROGRESS_INTERVAL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (listener.isCancelled()) {
                    for (var f : futures) f.cancel(true);
                    throw new IOException("Transfer cancelled");
                }
                long now = System.currentTimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    listener.onProgress(new TransferProgress(TransferDirection.DOWNLOAD, remotePath, localPath.toString(), transferred.get(), totalBytes));
                }
            }

            try { CompletableFuture.allOf(futures).join(); }
            catch (Exception e) { throw new IOException("Parallel download failed", e.getCause() != null ? e.getCause() : e); }

            listener.onProgress(new TransferProgress(TransferDirection.DOWNLOAD, remotePath, localPath.toString(), totalBytes, totalBytes));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void deletePath(SFTPClient sftpClient, String remotePath, boolean recursive) throws IOException {
        FileAttributes attributes = sftpClient.stat(remotePath);
        if (attributes.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            if (!recursive) {
                sftpClient.rmdir(remotePath);
                return;
            }

            for (RemoteResourceInfo child : sftpClient.ls(remotePath)) {
                if (isCurrentOrParentDirectory(child.getName())) {
                    continue;
                }
                deletePath(sftpClient, child.getPath(), true);
            }
            sftpClient.rmdir(remotePath);
            return;
        }

        sftpClient.rm(remotePath);
    }

    private SFTPClient newSftpClient(SshSession sshSession) throws IOException {
        SSHClient sshClient = sshSession.unwrap(SSHClient.class)
                .orElseThrow(() -> new SftpOperationException("SFTP requires an SSHJ-backed session"));
        return sshClient.newSFTPClient();
    }

    private boolean isCurrentOrParentDirectory(String name) {
        return ".".equals(name) || "..".equals(name);
    }

    private String fileName(String canonicalPath) {
        int index = canonicalPath.lastIndexOf('/');
        return index >= 0 ? canonicalPath.substring(index + 1) : canonicalPath;
    }
}
