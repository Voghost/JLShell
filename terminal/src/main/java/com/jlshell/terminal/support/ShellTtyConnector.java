package com.jlshell.terminal.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.session.ShellChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 core 层的 ShellChannel 适配为 JediTerm 所需的 TtyConnector。
 */
public class ShellTtyConnector implements TtyConnector {

    private static final Logger log = LoggerFactory.getLogger(ShellTtyConnector.class);

    private final String name;
    private final ShellChannel shellChannel;
    private final InputStreamReader reader;
    private final OutputStream outputStream;
    private final ExecutorService executorService;
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    public ShellTtyConnector(String name, ShellChannel shellChannel, ExecutorService executorService) {
        this.name = name;
        this.shellChannel = shellChannel;
        this.reader = new InputStreamReader(shellChannel.remoteOutput(), StandardCharsets.UTF_8);
        this.outputStream = shellChannel.remoteInput();
        this.executorService = executorService;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        try {
            int read = reader.read(buffer, offset, length);
            if (read < 0) {
                markDisconnected();
            }
            return read;
        } catch (IOException exception) {
            markDisconnected();
            throw exception;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        try {
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException exception) {
            markDisconnected();
            throw exception;
        }
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connected.get() && shellChannel.isOpen();
    }

    @Override
    public void resize(TermSize termSize) {
        if (!isConnected()) {
            return;
        }

        shellChannel.resize(new TerminalSize(termSize.getColumns(), termSize.getRows(), 0, 0))
                .exceptionally(throwable -> {
                    log.debug("Ignoring terminal resize failure for {}", name, throwable);
                    return null;
                });
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            closeFuture.join();
            return 0;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            return 1;
        }
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }

        connected.set(false);
        shellChannel.closeAsync()
                .whenCompleteAsync((unused, throwable) -> {
                    if (throwable != null) {
                        closeFuture.completeExceptionally(throwable);
                    } else {
                        closeFuture.complete(null);
                    }
                }, executorService);
    }

    @Override
    public boolean init(Questioner questioner) {
        return true;
    }

    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    private void markDisconnected() {
        connected.set(false);
        if (!closeStarted.get()) {
            closeFuture.complete(null);
        }
    }
}
