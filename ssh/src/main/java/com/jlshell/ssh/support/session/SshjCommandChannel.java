package com.jlshell.ssh.support.session;

import com.jlshell.core.session.CommandChannel;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SshjCommandChannel implements CommandChannel {
    private final Session session;
    private final Session.Command command;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Integer> completion = new CompletableFuture<>();

    SshjCommandChannel(Session session, Session.Command command, Duration timeout) {
        this.session = session;
        this.command = command;
        Thread.ofVirtual().name("ssh-exec-wait").start(() -> {
            try {
                command.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (command.isOpen()) {
                    cancel();
                    completion.completeExceptionally(new java.util.concurrent.TimeoutException("Remote process timed out"));
                } else completion.complete(command.getExitStatus());
            } catch (Exception error) { completion.completeExceptionally(error); }
        });
    }
    public InputStream stdout() { return command.getInputStream(); }
    public InputStream stderr() { return command.getErrorStream(); }
    public CompletableFuture<Integer> completion() { return completion; }
    public void cancel() {
        if (closed.get()) return;
        try { command.signal(Signal.TERM); } catch (Exception ignored) { }
        close();
    }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { command.close(); } catch (Exception ignored) { }
        try { session.close(); } catch (Exception ignored) { }
    }
}
