package com.jlshell.ssh.support.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jlshell.core.exception.SessionOperationException;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.session.ShellChannel;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * SSHJ 交互式 Shell 适配器。
 * 对 terminal 模块暴露稳定的流接口和窗口大小调整能力。
 */
public class SshjShellChannel implements ShellChannel {

    private final Session sshSession;
    private final Session.Shell shell;
    private final ExecutorService executorService;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public SshjShellChannel(Session sshSession, Session.Shell shell, ExecutorService executorService) {
        this.sshSession = sshSession;
        this.shell = shell;
        this.executorService = executorService;
    }

    @Override
    public InputStream remoteOutput() {
        return shell.getInputStream();
    }

    @Override
    public OutputStream remoteInput() {
        return shell.getOutputStream();
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public CompletableFuture<Void> resize(TerminalSize terminalSize) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new SessionOperationException("Shell channel already closed"));
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // 将 JediTerm 的窗口变化同步到远端 TTY，保证全屏程序布局正确。
                shell.changeWindowDimensions(
                        terminalSize.columns(),
                        terminalSize.rows(),
                        terminalSize.widthPixels(),
                        terminalSize.heightPixels()
                );
            } catch (IOException exception) {
                throw new SessionOperationException("Failed to resize shell channel", exception);
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (!open.compareAndSet(true, false)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                shell.close();
            } catch (IOException exception) {
                throw new SessionOperationException("Failed to close remote shell", exception);
            } finally {
                try {
                    // Shell 关闭后顺带释放对应的 SSH channel session，避免底层句柄泄漏。
                    sshSession.close();
                } catch (IOException exception) {
                    throw new SessionOperationException("Failed to close SSH channel session", exception);
                }
            }
        }, executorService);
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> type) {
        if (type.isInstance(shell)) {
            return Optional.of(type.cast(shell));
        }
        if (type.isInstance(sshSession)) {
            return Optional.of(type.cast(sshSession));
        }
        return Optional.empty();
    }
}
