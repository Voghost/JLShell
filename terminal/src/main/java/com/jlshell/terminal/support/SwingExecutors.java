package com.jlshell.terminal.support;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

/**
 * Swing EDT 调度工具。
 */
public final class SwingExecutors {

    private SwingExecutors() {
    }

    public static void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while switching to Swing EDT", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Failed to execute task on Swing EDT", exception.getCause());
        }
    }

    public static CompletableFuture<Void> runOnEdtAsync(Runnable runnable) {
        return supplyOnEdtAsync(() -> {
            runnable.run();
            return null;
        });
    }

    public static <T> CompletableFuture<T> supplyOnEdtAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
