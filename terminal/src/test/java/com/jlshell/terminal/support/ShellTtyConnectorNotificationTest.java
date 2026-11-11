package com.jlshell.terminal.support;

import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.session.ShellChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellTtyConnectorNotificationTest {
    @Test
    void reportsDistinctDirectoriesAndDisconnectOnSwingWithoutStartingFx() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var connector = new ShellTtyConnector("fixture", channel(
                    "\033]7;file://host/one\007\033]7;file://host/one\007\033]7;file://host/two\007"), executor);
            List<String> directories = new ArrayList<>();
            List<Boolean> deliveryThreads = new ArrayList<>();
            var disconnected = new AtomicReference<ShellTtyConnector.DisconnectReason>();
            var subscription = connector.onDirectoryChanged(path -> {
                directories.add(path);
                deliveryThreads.add(SwingUtilities.isEventDispatchThread());
            });
            connector.setOnDisconnected(reason -> {
                deliveryThreads.add(SwingUtilities.isEventDispatchThread());
                disconnected.set(reason);
            });
            flushEdt();
            drain(connector);
            flushEdt();
            assertEquals(List.of("", "/one", "/two"), directories);
            assertEquals("/two", connector.currentDirectory());
            assertEquals(ShellTtyConnector.DisconnectReason.REMOTE_CLOSED, disconnected.get());
            assertTrue(deliveryThreads.stream().allMatch(Boolean::booleanValue));
            subscription.close();
            subscription.close();
            connector.closeAfterDisconnect();
        }
    }

    @Test
    void closingSubscriptionSuppressesAlreadyQueuedInitialDelivery() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var connector = new ShellTtyConnector("fixture", channel(""), executor);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            List<String> directories = new ArrayList<>();
            SwingUtilities.invokeLater(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var subscription = connector.onDirectoryChanged(directories::add);
                subscription.close();
            } finally {
                release.countDown();
            }
            flushEdt();
            assertTrue(directories.isEmpty());
            connector.close();
        }
    }

    @Test
    void oneFailedListenerDoesNotPreventOtherDirectoryNotifications() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var connector = new ShellTtyConnector("fixture", channel("\033]7;file://host/one\007"), executor);
            List<String> directories = new ArrayList<>();
            var failing = connector.onDirectoryChanged(path -> { throw new IllegalStateException("fixture"); });
            var healthy = connector.onDirectoryChanged(directories::add);
            flushEdt();
            drain(connector);
            flushEdt();
            assertEquals(List.of("", "/one"), directories);
            failing.close();
            healthy.close();
            connector.closeAfterDisconnect();
        }
    }

    private static void drain(ShellTtyConnector connector) throws Exception {
        char[] buffer = new char[1024];
        while (connector.read(buffer, 0, buffer.length) >= 0) { }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static ShellChannel channel(String output) {
        return new ShellChannel() {
            private final InputStream input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            private final OutputStream sink = new ByteArrayOutputStream();
            public InputStream remoteOutput() { return input; }
            public OutputStream remoteInput() { return sink; }
            public boolean isOpen() { return true; }
            public CompletableFuture<Void> resize(TerminalSize size) { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> closeAsync() { return CompletableFuture.completedFuture(null); }
        };
    }
}
