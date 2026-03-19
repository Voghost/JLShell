package com.jlshell.ui.support;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;

/**
 * JavaFX UI 线程调度工具。
 */
public final class FxThread {

    private FxThread() {
    }

    public static void run(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
            return;
        }
        Platform.runLater(runnable);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        run(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
