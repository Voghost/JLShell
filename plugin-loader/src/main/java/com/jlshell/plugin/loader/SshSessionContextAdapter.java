package com.jlshell.plugin.loader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.session.ShellChannel;
import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.capability.InteractiveCommandExecutor;
import com.jlshell.plugin.api.capability.InteractiveSession;
import com.jlshell.plugin.api.capability.LogViewer;
import com.jlshell.plugin.api.capability.ServerStatusProvider;
import com.jlshell.plugin.api.model.CommandOutput;
import com.jlshell.plugin.api.model.CpuStatus;
import com.jlshell.plugin.api.model.DiskStatus;
import com.jlshell.plugin.api.model.MemoryStatus;
import com.jlshell.plugin.api.model.ProcessInfo;
import com.jlshell.plugin.api.model.RemoteFile;
import com.jlshell.sftp.model.TransferRequest;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.service.TransferProgressListener;

/**
 * Adapts {@link SshSession} to the plugin-api {@link SshSessionContext}.
 */
public class SshSessionContextAdapter implements SshSessionContext {

    private final SshSession session;
    private final SftpService sftpService;

    public SshSessionContextAdapter(SshSession session, SftpService sftpService) {
        this.session = session;
        this.sftpService = sftpService;
    }

    @Override
    public String sessionId() {
        return session.sessionId().toString();
    }

    @Override
    public String displayName() {
        return session.displayName();
    }

    @Override
    public String host() {
        return session.target().host();
    }

    @Override
    public int port() {
        return session.target().port();
    }

    @Override
    public String username() {
        return session.target().username();
    }

    @Override
    public CommandExecutor commandExecutor() {
        return new CommandExecutor() {
            @Override
            public CompletableFuture<CommandOutput> execute(String command) {
                return execute(command, Duration.ofSeconds(30));
            }

            @Override
            public CompletableFuture<CommandOutput> execute(String command, Duration timeout) {
                CommandRequest req = new CommandRequest(command, timeout, false, null);
                return session.execute(req).thenApply(result ->
                        new CommandOutput(result.stdout(), result.stderr(),
                                result.exitCode() == null ? -1 : result.exitCode()));
            }
        };
    }

    @Override
    public InteractiveCommandExecutor interactiveCommandExecutor() {
        return new InteractiveCommandExecutor() {
            @Override
            public CompletableFuture<InteractiveSession> start(String command) {
                return session.openShell(new ShellRequest("xterm-256color", null, null))
                        .thenApply(shellChannel -> new ShellChannelSessionAdapter(shellChannel, command));
            }
        };
    }

    @Override
    public FileExplorer fileExplorer() {
        return new FileExplorer() {
            @Override
            public CompletableFuture<List<RemoteFile>> listDirectory(String path) {
                return sftpService.listDirectory(session, path)
                        .thenApply(listing -> listing.entries().stream()
                                .map(e -> new RemoteFile(
                                        e.name(),
                                        e.path(),
                                        e.size(),
                                        e.isDirectory(),
                                        e.permissionString(),
                                        e.modifiedAt()))
                                .toList());
            }

            @Override
            public CompletableFuture<byte[]> readFile(String path) {
                Path tmpFile = Path.of(System.getProperty("java.io.tmpdir"),
                        "jlshell-download-" + session.sessionId() + "-" + Path.of(path).getFileName());
                TransferRequest req = new TransferRequest(tmpFile, path, null, 0);
                return sftpService.download(session, req, TransferProgressListener.NO_OP)
                        .thenCompose(unused -> {
                            try {
                                return CompletableFuture.completedFuture(
                                        java.nio.file.Files.readAllBytes(tmpFile));
                            } catch (java.io.IOException e) {
                                return CompletableFuture.failedFuture(e);
                            } finally {
                                try { java.nio.file.Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                            }
                        });
            }

            @Override
            public CompletableFuture<Void> writeFile(String path, byte[] content) {
                Path tmpFile = Path.of(System.getProperty("java.io.tmpdir"),
                        "jlshell-upload-" + session.sessionId() + "-" + Path.of(path).getFileName());
                try {
                    java.nio.file.Files.write(tmpFile, content);
                } catch (java.io.IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
                TransferRequest req = new TransferRequest(tmpFile, path, null, 0);
                return sftpService.upload(session, req, TransferProgressListener.NO_OP)
                        .whenComplete((unused, ex) -> {
                            try { java.nio.file.Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                        });
            }

            @Override
            public CompletableFuture<Void> deleteFile(String path) {
                return sftpService.delete(session, path, false);
            }
        };
    }

    @Override
    public LogViewer logViewer() {
        return new LogViewer() {
            @Override
            public CompletableFuture<List<String>> tail(String filePath, int lines) {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<Void> follow(String filePath, Consumer<String> lineConsumer, AtomicBoolean stop) {
                throw new UnsupportedOperationException("not yet implemented");
            }
        };
    }

    @Override
    public ServerStatusProvider serverStatus() {
        return new ServerStatusProvider() {
            @Override
            public CompletableFuture<CpuStatus> cpuStatus() {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<MemoryStatus> memoryStatus() {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<List<DiskStatus>> diskStatus() {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<List<ProcessInfo>> topProcesses(int limit) {
                throw new UnsupportedOperationException("not yet implemented");
            }
        };
    }

    /**
     * Adapts a {@link ShellChannel} to the plugin-api {@link InteractiveSession}.
     * When created, it sends the initial command to the shell and starts a background
     * thread to read output into a buffer.
     */
    private static class ShellChannelSessionAdapter implements InteractiveSession {

        private final ShellChannel channel;
        private final StringBuilder outputBuffer = new StringBuilder();
        private final Thread readerThread;
        private volatile boolean running = true;

        ShellChannelSessionAdapter(ShellChannel channel, String command) {
            this.channel = channel;

            // Start a background thread to continuously read from the shell's output stream
            readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(channel.remoteOutput()))) {
                    String line;
                    while (running && channel.isOpen()) {
                        line = reader.readLine();
                        if (line == null) break;
                        synchronized (outputBuffer) {
                            outputBuffer.append(line).append('\n');
                            outputBuffer.notifyAll();
                        }
                    }
                } catch (Exception ignored) {}
            }, "interactive-session-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Send the initial command
            try {
                channel.remoteInput().write((command + "\n").getBytes());
                channel.remoteInput().flush();
            } catch (Exception ignored) {}
        }

        @Override
        public String readOutput() {
            synchronized (outputBuffer) {
                String output = outputBuffer.toString();
                outputBuffer.setLength(0);
                return output;
            }
        }

        @Override
        public void writeInput(String input) {
            try {
                channel.remoteInput().write(input.getBytes());
                channel.remoteInput().flush();
            } catch (Exception ignored) {}
        }

        @Override
        public CompletableFuture<String> readUntil(String prompt, Duration timeout) {
            return CompletableFuture.supplyAsync(() -> {
                StringBuilder accumulated = new StringBuilder();
                long deadline = System.nanoTime() + timeout.toNanos();

                synchronized (outputBuffer) {
                    while (running) {
                        // Check if the prompt appears in the accumulated + buffered output
                        String current = accumulated.toString() + outputBuffer.toString();
                        if (current.contains(prompt)) {
                            // Transfer buffer to accumulated, then clear
                            accumulated.append(outputBuffer);
                            outputBuffer.setLength(0);
                            return accumulated.toString();
                        }

                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            accumulated.append(outputBuffer);
                            outputBuffer.setLength(0);
                            return accumulated.toString();
                        }

                        try {
                            outputBuffer.wait(Math.min(remaining / 1_000_000, 500));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            accumulated.append(outputBuffer);
                            outputBuffer.setLength(0);
                            return accumulated.toString();
                        }
                    }
                }
                return accumulated.toString();
            });
        }

        @Override
        public void close() {
            running = false;
            synchronized (outputBuffer) {
                outputBuffer.notifyAll();
            }
            try { channel.close(); } catch (Exception ignored) {}
            readerThread.interrupt();
        }
    }
}
