package com.jlshell.ssh.support.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jlshell.core.exception.SessionOperationException;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.session.ShellChannel;
import com.jlshell.core.session.SshSession;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSHJ 会话适配器。
 * 将 SSHJ 的 Session、Shell、Command 能力映射为 core 层统一接口。
 */
public class SshjSession implements SshSession {

    private static final Logger log = LoggerFactory.getLogger(SshjSession.class);

    private final SessionId sessionId;
    private final String displayName;
    private final ConnectionTarget target;
    private final SSHClient client;
    private final ExecutorService executorService;
    private final Instant connectedAt;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CONNECTED);

    public SshjSession(
            SessionId sessionId,
            String displayName,
            ConnectionTarget target,
            SSHClient client,
            ExecutorService executorService
    ) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.target = target;
        this.client = client;
        this.executorService = executorService;
        this.connectedAt = Instant.now();
    }

    @Override
    public SessionId sessionId() {
        return sessionId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public ConnectionTarget target() {
        return target;
    }

    @Override
    public SessionState state() {
        return state.get();
    }

    @Override
    public Instant connectedAt() {
        return connectedAt;
    }

    @Override
    public CompletableFuture<CommandResult> execute(CommandRequest request) {
        ensureConnected();
        // 命令执行可能阻塞于网络或远程程序输出，因此始终异步提交。
        CompletableFuture<CommandResult> future = CompletableFuture.supplyAsync(
                () -> executeBlocking(request),
                executorService
        );
        return future.orTimeout(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<ShellChannel> openShell(ShellRequest request) {
        ensureConnected();
        return CompletableFuture.supplyAsync(() -> openShellBlocking(request), executorService);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        if (state.get() == SessionState.CLOSED) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            state.set(SessionState.DISCONNECTING);
            try {
                client.disconnect();
                client.close();
                state.set(SessionState.CLOSED);
                log.info("SSH session {} closed", sessionId);
            } catch (IOException exception) {
                state.set(SessionState.FAILED);
                throw new SessionOperationException("Failed to disconnect SSH session " + sessionId, exception);
            }
        }, executorService);
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> type) {
        if (type.isInstance(client)) {
            return Optional.of(type.cast(client));
        }
        return Optional.empty();
    }

    private CommandResult executeBlocking(CommandRequest request) {
        Instant startedAt = Instant.now();
        try (Session sshSession = client.startSession()) {
            if (request.allocatePty()) {
                // 某些命令依赖 PTY 才会输出彩色或按交互模式工作。
                sshSession.allocateDefaultPTY();
            }

            Session.Command command = sshSession.exec(request.command());
            try {
                // 等待远端命令结束后再收集输出，适合一次性命令执行模型。
                command.join();
                return new CommandResult(
                        request.command(),
                        command.getExitStatus(),
                        readAll(command.getInputStream()),
                        readAll(command.getErrorStream()),
                        Duration.between(startedAt, Instant.now())
                );
            } finally {
                command.close();
            }
        } catch (IOException exception) {
            throw new SessionOperationException("Failed to execute remote command: " + request.command(), exception);
        }
    }

    private ShellChannel openShellBlocking(ShellRequest request) {
        try {
            Session sshSession = client.startSession();
            // 打开 Shell 前先分配 PTY，保证 ANSI/窗口尺寸行为符合终端预期。
            sshSession.allocatePTY(
                    request.terminalType(),
                    request.terminalSize().columns(),
                    request.terminalSize().rows(),
                    request.terminalSize().widthPixels(),
                    request.terminalSize().heightPixels(),
                    java.util.Map.of(PTYMode.ECHO, 1)
            );

            Session.Shell shell = sshSession.startShell();
            return new SshjShellChannel(sshSession, shell, executorService);
        } catch (IOException exception) {
            throw new SessionOperationException("Failed to open interactive shell for session " + sessionId, exception);
        }
    }

    private String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private void ensureConnected() {
        if (state.get() != SessionState.CONNECTED) {
            // 这里尽早失败，防止调用方误以为命令已被提交到远端。
            throw new SessionOperationException("Session is not connected: " + sessionId);
        }
    }
}
