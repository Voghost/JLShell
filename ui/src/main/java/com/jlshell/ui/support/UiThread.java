package com.jlshell.ui.support;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** 原生界面线程调度，当前位于 EDT 时按调用顺序立即执行。 */
public final class UiThread {
    private UiThread() { }

    public static void run(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        run(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
