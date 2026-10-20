package com.jlshell.sftp.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.jlshell.core.session.SshSession;
import com.jlshell.core.support.PerformanceTrace;
import com.jlshell.sftp.exception.SftpOperationException;
import com.jlshell.sftp.exception.TransferCancelledException;
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
 * 上传下载显式采用分块流式拷贝，确保大文件不会整体读入内存。
 */
public class SshjSftpService implements SftpService {

    private static final Logger log = LoggerFactory.getLogger(SshjSftpService.class);

    private final ExecutorService executorService;
    private final int uploadPipelineDepth;

    public SshjSftpService(ExecutorService executorService) {
        this(executorService, Math.max(1, Math.min(128, Integer.getInteger("jlshell.sftp.uploadPipelineDepth", 64))));
    }

    SshjSftpService(ExecutorService executorService, int uploadPipelineDepth) {
        if (uploadPipelineDepth < 1 || uploadPipelineDepth > 128) throw new IllegalArgumentException("Invalid upload pipeline depth");
        this.executorService = executorService;
        this.uploadPipelineDepth = uploadPipelineDepth;
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
        TransferProgressListener progressListener = listener == null ? TransferProgressListener.NO_OP : listener;
        var timing = PerformanceTrace.start("sftp-upload", "file");
        var queue = timing.step("executor-queue");
        try {
            return CompletableFuture.runAsync(() -> {
                queue.close();
                var metrics = new UploadMetrics();
                try {
                    doUpload(sshSession, request, progressListener, timing, metrics);
                    metrics.success = true;
                    timing.finish("success");
                } catch (TransferCancelledException cancelled) {
                    timing.finish("cancelled");
                    throw cancelled;
                } catch (RuntimeException | Error error) {
                    timing.failed(error);
                    throw error;
                } finally { metrics.log(timing.id()); }
            }, executorService);
        } catch (RuntimeException rejected) {
            queue.failed(rejected);
            queue.close();
            timing.failed(rejected);
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @Override
    public CompletableFuture<String> md5(SshSession sshSession, String remotePath,
                                          java.util.function.BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            var timing = PerformanceTrace.start("sftp-md5", "file");
            try {
                com.jlshell.sftp.service.FileChecksums.checkCancelled(cancelled);
                String result;
                try (SFTPClient client = newSftpClient(sshSession)) {
                    SSHClient ssh = sshSession.unwrap(SSHClient.class).orElseThrow();
                    result = RemoteFileChecksums.md5(ssh, client, remotePath, cancelled, timing);
                }
                timing.finish("success");
                return result;
            } catch (TransferCancelledException stopped) {
                timing.finish("cancelled");
                throw stopped;
            } catch (IOException error) {
                timing.failed(error);
                throw new SftpOperationException("Failed to calculate remote MD5", error);
            } catch (RuntimeException | Error error) { timing.failed(error); throw error; }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> download(
            SshSession sshSession,
            TransferRequest request,
            TransferProgressListener listener
    ) {
        TransferProgressListener progressListener = listener == null ? TransferProgressListener.NO_OP : listener;
        return CompletableFuture.runAsync(() -> doDownload(sshSession, request, progressListener), executorService);
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

    private void doUpload(SshSession sshSession, TransferRequest request, TransferProgressListener listener,
                          PerformanceTrace timing, UploadMetrics metrics) {
        Path localPath = request.localPath();
        if (!Files.exists(localPath) || Files.isDirectory(localPath)) {
            throw new SftpOperationException("Local upload source must be an existing file: " + localPath);
        }

        try (SFTPClient sftpClient = timing.call("sftp-channel-open", () -> newSftpClient(sshSession))) {
            Files.createDirectories(localPath.toAbsolutePath().getParent());

            long totalBytes = Files.size(localPath);
            metrics.fileBytes = totalBytes;
            long offset = request.resumeMode() == TransferResumeMode.RESUME_IF_POSSIBLE
                    ? existingRemoteSize(sftpClient, request.remotePath())
                    : 0L;
            if (offset > totalBytes) {
                offset = 0L;
            }
            metrics.resumedBytes = offset;

            TransferProgress started = new TransferProgress(
                    TransferDirection.UPLOAD,
                    localPath.toString(),
                    request.remotePath(),
                    offset,
                    totalBytes
            );
            listener.onStarted(started);

            try {
                uploadStreaming(sftpClient, localPath, request, offset, totalBytes, listener, timing, metrics);
                listener.onCompleted(new TransferProgress(
                        TransferDirection.UPLOAD,
                        localPath.toString(),
                        request.remotePath(),
                        totalBytes,
                        totalBytes
                ));
            } catch (TransferCancelledException e) {
                deleteRemoteFileQuietly(sftpClient, request.remotePath());
                listener.onFailed(started, e);
                throw e;
            } catch (IOException e) {
                // Do not retry with put(): it can overwrite a resumed file and bypass cancellation.
                listener.onFailed(started, e);
                throw new SftpOperationException("Failed to upload file to remote path: " + request.remotePath(), e);
            } catch (Throwable throwable) {
                listener.onFailed(started, throwable);
                throw throwable;
            }
        } catch (TransferCancelledException e) {
            throw e;
        } catch (SftpOperationException e) {
            throw e;
        } catch (IOException exception) {
            throw new SftpOperationException("Failed to upload file to remote path: " + request.remotePath(), exception);
        }
    }

    private void uploadStreaming(
            SFTPClient sftpClient,
            Path localPath,
            TransferRequest request,
            long offset,
            long totalBytes,
            TransferProgressListener listener,
            PerformanceTrace timing,
            UploadMetrics metrics
    ) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(localPath), request.bufferSize());
             RemoteFile remoteFile = sftpClient.open(request.remotePath(),
                     offset > 0
                             ? EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
                             : EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {

            skipFully(inputStream, offset);
            int packetSize = sftpClient.getSFTPEngine().getSubsystem().getRemoteMaxPacketSize()
                    - remoteFile.getOutgoingPacketOverhead();
            if (packetSize <= 0) throw new IOException("Invalid negotiated SFTP packet size");
            // SSHJ does not split OutputStream writes. Respect the negotiated channel packet limit.
            int bufferSize = Math.min(request.bufferSize(), Math.min(packetSize, 64 * 1024));
            metrics.packetBytes = bufferSize;
            metrics.pendingWrites = uploadPipelineDepth;
            try (OutputStream outputStream = remoteFile.new RemoteFileOutputStream(offset, uploadPipelineDepth)) {
                long writing = System.nanoTime();
                copyLocalToRemote(inputStream, outputStream, bufferSize, offset, totalBytes, request, listener, metrics);
                // All outstanding WRITE responses must succeed before onCompleted can run.
                long flushing = System.nanoTime();
                timing.call("final-write-confirmations", () -> { outputStream.flush(); return null; });
                metrics.finalAckNanos = System.nanoTime() - flushing;
                metrics.payloadNanos = System.nanoTime() - writing;
                if (listener.isCancelled()) throw new TransferCancelledException();
            }
        }
    }

    private void doDownload(SshSession sshSession, TransferRequest request, TransferProgressListener listener) {
        Path localPath = request.localPath();
        try (SFTPClient sftpClient = newSftpClient(sshSession)) {
            String remotePath = sftpClient.canonicalize(request.remotePath());
            long totalBytes = sftpClient.size(remotePath);

            if (localPath.toAbsolutePath().getParent() != null) {
                Files.createDirectories(localPath.toAbsolutePath().getParent());
            }

            long offset = request.resumeMode() == TransferResumeMode.RESUME_IF_POSSIBLE && Files.exists(localPath)
                    ? Files.size(localPath)
                    : 0L;
            if (offset > totalBytes) {
                offset = 0L;
            }

            TransferProgress started = new TransferProgress(
                    TransferDirection.DOWNLOAD,
                    remotePath,
                    localPath.toString(),
                    offset,
                    totalBytes
            );
            listener.onStarted(started);

            StandardOpenOption[] openOptions = offset > 0
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

            try (RemoteFile remoteFile = sftpClient.open(remotePath, EnumSet.of(OpenMode.READ));
                 OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(localPath, openOptions), request.bufferSize())) {

                copyRemoteToLocal(remoteFile, outputStream, offset, totalBytes, remotePath, localPath, request, listener);
                listener.onCompleted(new TransferProgress(
                        TransferDirection.DOWNLOAD,
                        remotePath,
                        localPath.toString(),
                        totalBytes,
                        totalBytes
                ));
            } catch (TransferCancelledException e) {
                deleteLocalFileQuietly(localPath);
                listener.onFailed(started, e);
                throw e;
            } catch (Throwable throwable) {
                listener.onFailed(started, throwable);
                throw throwable;
            }
        } catch (TransferCancelledException e) {
            throw e;
        } catch (IOException exception) {
            throw new SftpOperationException("Failed to download remote file: " + request.remotePath(), exception);
        }
    }

    private void copyLocalToRemote(
            InputStream inputStream,
            OutputStream outputStream,
            int bufferSize,
            long offset,
            long totalBytes,
            TransferRequest request,
            TransferProgressListener listener,
            UploadMetrics metrics
    ) throws IOException {
        byte[] buffer = new byte[bufferSize];
        long position = offset;
        if (listener.isCancelled()) throw new TransferCancelledException();
        int read;
        while (true) {
            long reading = System.nanoTime();
            read = inputStream.read(buffer);
            metrics.readNanos += System.nanoTime() - reading;
            if (read < 0) break;
            if (read == 0) {
                continue;
            }
            if (listener.isCancelled()) {
                throw new TransferCancelledException();
            }
            long writing = System.nanoTime();
            outputStream.write(buffer, 0, read);
            metrics.writeNanos += System.nanoTime() - writing;
            metrics.queuedBytes += read;
            position += read;
            long notifying = System.nanoTime();
            listener.onProgress(new TransferProgress(
                    TransferDirection.UPLOAD,
                    request.localPath().toString(),
                    request.remotePath(),
                    position,
                    totalBytes
            ));
            metrics.listenerNanos += System.nanoTime() - notifying;
        }
    }

    private static final class UploadMetrics {
        long fileBytes, resumedBytes, queuedBytes, readNanos, writeNanos, listenerNanos, finalAckNanos, payloadNanos;
        int packetBytes, pendingWrites;
        boolean success;

        void log(String traceId) {
            if (!Boolean.parseBoolean(System.getProperty("jlshell.performance.enabled", "true"))) return;
            double rate = success && payloadNanos > 0 ? queuedBytes / 1048576.0 / (payloadNanos / 1e9) : 0;
            log.info("SFTP upload traceId={} success={} fileBytes={} resumedBytes={} queuedBytes={} packetBytes={} pendingWrites={} localReadMs={} writeMs={} listenerMs={} finalAckMs={} payloadMs={} MiBps={}",
                    traceId, success, fileBytes, resumedBytes, queuedBytes, packetBytes, pendingWrites,
                    readNanos / 1_000_000, writeNanos / 1_000_000, listenerNanos / 1_000_000,
                    finalAckNanos / 1_000_000, payloadNanos / 1_000_000,
                    String.format(java.util.Locale.ROOT, "%.2f", rate));
        }
    }

    private void copyRemoteToLocal(
            RemoteFile remoteFile,
            OutputStream outputStream,
            long offset,
            long totalBytes,
            String remotePath,
            Path localPath,
            TransferRequest request,
            TransferProgressListener listener
    ) throws IOException {
        byte[] buffer = new byte[request.bufferSize()];
        long position = offset;
        while (position < totalBytes) {
            if (listener.isCancelled()) {
                throw new TransferCancelledException();
            }
            int read = remoteFile.read(position, buffer, 0, (int) Math.min(buffer.length, totalBytes - position));
            if (read < 0) {
                break;
            }
            outputStream.write(buffer, 0, read);
            outputStream.flush();
            position += read;
            listener.onProgress(new TransferProgress(
                    TransferDirection.DOWNLOAD,
                    remotePath,
                    localPath.toString(),
                    position,
                    totalBytes
            ));
        }
    }

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
        if (!sshClient.isConnected() || !sshClient.getTransport().isRunning()) {
            throw new SftpOperationException("SFTP session is not connected: " + sshSession.sessionId());
        }
        return sshClient.newSFTPClient();
    }

    private long existingRemoteSize(SFTPClient sftpClient, String remotePath) {
        try {
            FileAttributes attributes = sftpClient.statExistence(remotePath);
            if (attributes == null || attributes.getType() != net.schmizz.sshj.sftp.FileMode.Type.REGULAR) {
                return 0L;
            }
            return attributes.getSize();
        } catch (IOException exception) {
            log.debug("Unable to inspect existing remote size for {}", remotePath, exception);
            return 0L;
        }
    }

    private void skipFully(InputStream inputStream, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    break;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private boolean isCurrentOrParentDirectory(String name) {
        return ".".equals(name) || "..".equals(name);
    }

    private String fileName(String canonicalPath) {
        int index = canonicalPath.lastIndexOf('/');
        return index >= 0 ? canonicalPath.substring(index + 1) : canonicalPath;
    }

    private void deleteRemoteFileQuietly(SFTPClient sftpClient, String remotePath) {
        try {
            sftpClient.rm(remotePath);
        } catch (Exception e) {
            log.debug("Failed to delete partial remote file after cancel: {}", remotePath, e);
        }
    }

    private void deleteLocalFileQuietly(Path localPath) {
        try {
            Files.deleteIfExists(localPath);
        } catch (Exception e) {
            log.debug("Failed to delete partial local file after cancel: {}", localPath, e);
        }
    }
}
