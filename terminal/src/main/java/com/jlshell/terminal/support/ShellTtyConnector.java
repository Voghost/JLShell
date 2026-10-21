package com.jlshell.terminal.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.session.ShellChannel;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 core 层的 ShellChannel 适配为 JediTerm 所需的 TtyConnector。
 * 同时解析终端输出中的 OSC 7 序列以追踪当前工作目录。
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

    /** 终端当前工作目录，通过 OSC 7 序列追踪 */
    private final StringProperty cwdProperty = new SimpleStringProperty("");

    private final Osc7DirectoryParser directoryParser = new Osc7DirectoryParser(path ->
            Platform.runLater(() -> {
                if (!path.equals(cwdProperty.get())) cwdProperty.set(path);
            }));
    private ShellDirectoryIntegration directoryIntegration;

    /** 过滤程序内部命令的单次 PTY 回显；正常终端输出仍原样交给 JediTerm。 */
    private final TerminalEchoSuppressor echoSuppressor = new TerminalEchoSuppressor();
    private final StringBuilder filteredOutput = new StringBuilder();

    /** 连接断开回调（由 UI 层注册，用于显示断连提示和重连按钮） */
    private volatile Consumer<DisconnectReason> onDisconnected;

    /** 断连原因，区分远程关闭、网络异常、用户主动关闭等 */
    public enum DisconnectReason {
        /** 远端关闭了 Shell（EOF） */
        REMOTE_CLOSED,
        /** 网络 I/O 异常（连接超时、keep-alive 失败、网络中断等） */
        IO_ERROR,
        /** 用户主动关闭 */
        USER_CLOSE
    }

    public ShellTtyConnector(String name, ShellChannel shellChannel, ExecutorService executorService) {
        this.name = name;
        this.shellChannel = shellChannel;
        this.reader = new InputStreamReader(shellChannel.remoteOutput(), StandardCharsets.UTF_8);
        this.outputStream = shellChannel.remoteInput();
        this.executorService = executorService;
    }

    /** 返回终端当前工作目录的可观察属性。 */
    public StringProperty cwdProperty() {
        return cwdProperty;
    }

    /**
     * 隐藏下一次指定输入在 PTY 输出中的回显。
     * OSC 等由命令产生的真实输出不会被过滤。
     */
    public void suppressNextEcho(String text) {
        echoSuppressor.suppressNext(text);
    }

    /** Called on the I/O executor before JediTerm starts reading or accepting user input. */
    public void initializeDirectoryReporting() throws IOException {
        directoryIntegration = new ShellDirectoryIntegration();
        write(directoryIntegration.command());
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        try {
            while (true) {
                int queued = drainFilteredOutput(buffer, offset, length);
                if (queued > 0) {
                    return queued;
                }

                if (directoryIntegration != null && directoryIntegration.pending()) {
                    if (directoryIntegration.expired()) {
                        appendFilteredOutput(directoryIntegration.release());
                        continue;
                    }
                    if (!reader.ready()) {
                        try { Thread.sleep(10); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        continue;
                    }
                }
                char[] raw = new char[Math.max(256, length)];
                int read = reader.read(raw, 0, raw.length);
                if (read < 0) {
                    if (directoryIntegration != null && directoryIntegration.pending()) {
                        appendFilteredOutput(directoryIntegration.release());
                        int remaining = drainFilteredOutput(buffer, offset, length);
                        if (remaining > 0) return remaining;
                    }
                    log.info("[TtyConnector] '{}' received EOF (remote closed shell)", name);
                    markDisconnected(DisconnectReason.REMOTE_CLOSED);
                    return -1;
                }

                // 先扫描原始输出，确保被终端显示层过滤的内部命令不会影响 OSC 7 解析。
                directoryParser.accept(raw, 0, read);
                String visible = echoSuppressor.filter(raw, 0, read);
                appendFilteredOutput(directoryIntegration == null ? visible : directoryIntegration.filter(visible));
            }
        } catch (IOException exception) {
            String msg = exception.getMessage();
            // SocketTimeoutException 是 socket read timeout 触发，说明 keepalive 可能已失败
            if (msg != null && (msg.contains("timed out") || msg.contains("Timeout") || msg.contains("reset"))) {
                log.warn("[TtyConnector] '{}' read timeout/reset — connection likely dead: {}", name, msg);
            } else {
                log.warn("[TtyConnector] '{}' read IOException: {}", name, msg);
            }
            markDisconnected(DisconnectReason.IO_ERROR);
            throw exception;
        }
    }

    private synchronized void appendFilteredOutput(String text) {
        filteredOutput.append(text);
    }

    private synchronized int drainFilteredOutput(char[] target, int offset, int length) {
        int count = Math.min(length, filteredOutput.length());
        if (count == 0) {
            return 0;
        }
        filteredOutput.getChars(0, count, target, offset);
        filteredOutput.delete(0, count);
        return count;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        try {
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException exception) {
            log.warn("[TtyConnector] '{}' write failed (connection likely dead): connected={}, channelOpen={}, error={}",
                    name, connected.get(), shellChannel.isOpen(), exception.getMessage());
            markDisconnected(DisconnectReason.IO_ERROR);
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
    public synchronized boolean ready() throws IOException {
        return !filteredOutput.isEmpty() || reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        closeInternal(true);
    }

    public void closeAfterDisconnect() {
        closeInternal(false);
    }

    private void closeInternal(boolean userInitiated) {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }

        boolean wasConnected = connected.getAndSet(false);
        if (userInitiated && wasConnected) {
            log.info("[TtyConnector] '{}' close() called (user-initiated)", name);
        } else {
            log.info("[TtyConnector] '{}' close() called (disconnect cleanup), userInitiated={}, wasConnected={}",
                    name, userInitiated, wasConnected);
        }
        CompletableFuture<Void> channelClose = shellChannel.closeAsync();
        if (!userInitiated) {
            closeFuture.complete(null);
            channelClose.whenCompleteAsync((unused, throwable) -> {
                if (throwable != null) {
                    log.debug("[TtyConnector] '{}' disconnect cleanup close failed: {}",
                            name, throwable.getMessage());
                }
            }, executorService);
            return;
        }
        channelClose.whenCompleteAsync((unused, throwable) -> {
            if (throwable != null) {
                closeFuture.completeExceptionally(throwable);
            } else {
                closeFuture.complete(null);
            }
        }, executorService);
    }

    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    /** 注册连接断开回调。回调在 JavaFX 应用线程执行。 */
    public void setOnDisconnected(Consumer<DisconnectReason> callback) {
        this.onDisconnected = callback;
    }

    private void markDisconnected(DisconnectReason reason) {
        if (!connected.compareAndSet(true, false)) {
            return; // 已经断开，不重复触发
        }
        Consumer<DisconnectReason> cb = onDisconnected;
        log.info("[TtyConnector] '{}' marked disconnected, reason={}, callbackRegistered={}, closeStarted={}",
                name, reason, cb != null, closeStarted.get());
        if (!closeStarted.get()) {
            closeFuture.complete(null);
        }
        // 通知 UI 层
        if (cb != null && reason != DisconnectReason.USER_CLOSE) {
            Platform.runLater(() -> cb.accept(reason));
        }
    }
}
