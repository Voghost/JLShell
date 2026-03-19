package com.jlshell.terminal.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将本地 Shell（通过 pty4j PTY）适配为 JediTerm TtyConnector。
 */
public class LocalShellTtyConnector implements TtyConnector {

    private static final Logger log = LoggerFactory.getLogger(LocalShellTtyConnector.class);

    private final String name;
    private final PtyProcess process;
    private final InputStreamReader reader;
    private final OutputStream outputStream;
    private final ExecutorService executorService;
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    public LocalShellTtyConnector(String name, String[] command, int columns, int rows,
                                   ExecutorService executorService) throws IOException {
        this.name = name;
        this.executorService = executorService;

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        this.process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(env)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setConsole(false)
                .start();

        this.reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        this.outputStream = process.getOutputStream();

        // Watch for process exit
        executorService.submit(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                markDisconnected();
            }
        });
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        try {
            int read = reader.read(buffer, offset, length);
            if (read < 0) markDisconnected();
            return read;
        } catch (IOException e) {
            markDisconnected();
            throw e;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        try {
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            markDisconnected();
            throw e;
        }
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process.isAlive();
    }

    @Override
    public void resize(TermSize termSize) {
        try {
            process.setWinSize(new com.pty4j.WinSize(termSize.getColumns(), termSize.getRows()));
        } catch (Exception e) {
            log.debug("PTY resize failed for {}", name, e);
        }
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            closeFuture.join();
            return process.exitValue();
        } catch (Exception e) {
            if (e instanceof InterruptedException ie) throw ie;
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
        if (!closeStarted.compareAndSet(false, true)) return;
        connected.set(false);
        CompletableFuture.runAsync(() -> {
            process.destroy();
            closeFuture.complete(null);
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
