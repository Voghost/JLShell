package com.jlshell.ui.service;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一管理手动和关闭会话后的延迟内存回收请求。
 */
public class MemoryReclaimService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryReclaimService.class);
    private static final long AUTO_DELAY_MILLIS = 5_000;
    private static final long MANUAL_STATUS_DELAY_MILLIS = 800;

    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicReference<ScheduledTask> pendingAutoTask = new AtomicReference<>();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public MemoryReclaimService() {
        scheduler = new ScheduledThreadPoolExecutor(1, daemonFactory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void requestAfterWorkspaceClosed(boolean noOpenWorkspaces) {
        if (!noOpenWorkspaces || scheduler.isShutdown()) {
            return;
        }
        ScheduledTask task = new ScheduledTask(() -> reclaim("workspace-idle"));
        ScheduledTask previous = pendingAutoTask.getAndSet(task);
        if (previous != null) {
            previous.cancel();
        }
        task.schedule();
    }

    public void requestNow() {
        if (!scheduler.isShutdown()) {
            scheduler.execute(() -> reclaim("manual"));
        }
    }

    public void requestNowWithStatus(java.util.function.Consumer<String> statusConsumer) {
        Objects.requireNonNull(statusConsumer, "statusConsumer");
        if (scheduler.isShutdown()) {
            Platform.runLater(() -> statusConsumer.accept(""));
            return;
        }
        long before = usedHeapBytes();
        scheduler.execute(() -> {
            reclaim("manual");
            scheduler.schedule(() -> {
                long after = usedHeapBytes();
                String message = formatResult(before, after);
                Platform.runLater(() -> statusConsumer.accept(message));
            }, MANUAL_STATUS_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        });
    }

    private void reclaim(String reason) {
        long before = usedHeapBytes();
        System.gc();
        long after = usedHeapBytes();
        log.info("Memory reclaim requested ({}): heap {} -> {}", reason,
                formatBytes(before), formatBytes(after));
    }

    private long usedHeapBytes() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        return heap.getUsed();
    }

    private String formatResult(long before, long after) {
        long reclaimed = Math.max(0, before - after);
        return formatBytes(before) + " -> " + formatBytes(after)
                + " (" + formatBytes(reclaimed) + ")";
    }

    private String formatBytes(long bytes) {
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "jlshell-memory-reclaim");
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private final class ScheduledTask implements Runnable {
        private final Runnable delegate;
        private java.util.concurrent.ScheduledFuture<?> future;

        private ScheduledTask(Runnable delegate) {
            this.delegate = delegate;
        }

        private void schedule() {
            future = scheduler.schedule(this, AUTO_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void run() {
            if (pendingAutoTask.compareAndSet(this, null)) {
                delegate.run();
            }
        }
    }
}
