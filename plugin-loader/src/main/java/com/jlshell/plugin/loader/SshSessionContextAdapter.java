package com.jlshell.plugin.loader;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.capability.FileExplorer;
import com.jlshell.plugin.api.capability.LogViewer;
import com.jlshell.plugin.api.capability.ServerStatusProvider;
import com.jlshell.plugin.api.model.CommandOutput;
import com.jlshell.plugin.api.model.CpuStatus;
import com.jlshell.plugin.api.model.DiskStatus;
import com.jlshell.plugin.api.model.MemoryStatus;
import com.jlshell.plugin.api.model.ProcessInfo;
import com.jlshell.plugin.api.model.RemoteFile;

/**
 * Adapts {@link SshSession} to the plugin-api {@link SshSessionContext}.
 */
public class SshSessionContextAdapter implements SshSessionContext {

    private final SshSession session;

    public SshSessionContextAdapter(SshSession session) {
        this.session = session;
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
    public FileExplorer fileExplorer() {
        return new FileExplorer() {
            @Override
            public CompletableFuture<List<RemoteFile>> listDirectory(String path) {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<byte[]> readFile(String path) {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<Void> writeFile(String path, byte[] content) {
                throw new UnsupportedOperationException("not yet implemented");
            }

            @Override
            public CompletableFuture<Void> deleteFile(String path) {
                throw new UnsupportedOperationException("not yet implemented");
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
}
