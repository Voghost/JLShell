package com.jlshell.app;

import com.jlshell.ui.support.FxThread;
import javafx.application.Platform;
import javafx.stage.Stage;
import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 独立空配置的真实窗口性能探针；通过 measure_workbench.py 启动，不建立 SSH 连接。 */
public class WorkbenchPerformanceProbe {
    static long started = System.nanoTime();
    static <T> T fx(Supplier<T> task) throws Exception { return FxThread.supplyAsync(task).get(30, TimeUnit.SECONDS); }
    static <T> T edt(Supplier<T> task) throws Exception {
        var f = new CompletableFuture<T>();
        SwingUtilities.invokeLater(() -> { try { f.complete(task.get()); } catch(Throwable e) { f.completeExceptionally(e); } });
        return f.get(30,TimeUnit.SECONDS);
    }
    static void metric(String phase) throws Exception {
        var memory = ManagementFactory.getMemoryMXBean();
        long rss = -1;
        long peakWorkingSet = -1, privateCommitted = -1;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            long[] counters = WindowsMemory.read();
            rss = counters[0]; peakWorkingSet = counters[1]; privateCommitted = counters[2];
        } else {
            var p = new ProcessBuilder("ps","-o","rss=","-p",Long.toString(ProcessHandle.current().pid())).start();
            rss = Long.parseLong(new String(p.getInputStream().readAllBytes()).trim()) * 1024;
        }
        System.out.printf("PERF phase=%s elapsedMs=%d heapUsed=%d heapCommitted=%d nonHeap=%d rss=%d classes=%d peakWorkingSet=%d privateCommitted=%d%n",
                phase,(System.nanoTime()-started)/1_000_000,memory.getHeapMemoryUsage().getUsed(),memory.getHeapMemoryUsage().getCommitted(),
                memory.getNonHeapMemoryUsage().getUsed(),rss,ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                peakWorkingSet, privateCommitted);
    }

    /** PROCESS_MEMORY_COUNTERS_EX；仅探针依赖现有 JNA，不增加产品依赖。 */
    private static final class WindowsMemory {
        public interface Psapi extends com.sun.jna.win32.StdCallLibrary {
            boolean GetProcessMemoryInfo(com.sun.jna.platform.win32.WinNT.HANDLE process,
                                         com.sun.jna.Pointer counters, int size);
        }
        private static final Psapi API = com.sun.jna.Native.load("psapi", Psapi.class);
        static long[] read() {
            int size = 8 + 9 * com.sun.jna.Native.SIZE_T_SIZE;
            try (var counters = new com.sun.jna.Memory(size)) {
                counters.clear(); counters.setInt(0, size);
                if (!API.GetProcessMemoryInfo(com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(), counters, size))
                    throw new IllegalStateException("GetProcessMemoryInfo failed: " + com.sun.jna.Native.getLastError());
                return new long[]{sizeT(counters, 1), sizeT(counters, 0), sizeT(counters, 8)};
            }
        }
        private static long sizeT(com.sun.jna.Memory counters, int index) {
            long offset = 8L + (long) index * com.sun.jna.Native.SIZE_T_SIZE;
            return com.sun.jna.Native.SIZE_T_SIZE == 8 ? counters.getLong(offset)
                    : Integer.toUnsignedLong(counters.getInt(offset));
        }
    }
    public static void main(String[] args) throws Exception {
        try {
            var ready = new CompletableFuture<Void>();
            Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
            ready.get(20,TimeUnit.SECONDS); metric("toolkit");
            new JlShellDesktopApplication().init();
            metric("fonts");
            AppContext context = fx(AppContext::new); metric("context");
            Stage owner = fx(() -> { Stage s=new Stage();s.setScene(context.getMainWindow().createScene(s));return s; });
            metric("scene");
            var shown=fx(() -> context.getMainWindow().showSwingWindow(owner,()->{}));
            shown.get(20,TimeUnit.SECONDS); edt(()->null); metric("window");
            Thread.sleep(1000); metric("idle");
            for (int iteration=0;iteration<3;iteration++) {
                long open=System.nanoTime();
                var done = new CompletableFuture<Void>();
                Platform.runLater(()->{try {context.getMainWindow().openPreferences(owner);done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});
                JDialog settings=null;
                for(int i=0;i<600&&settings==null;i++) {
                    settings=edt(()->{for(Window w:Window.getWindows()) if(w instanceof JDialog d&&d.isShowing()&&"jlshell.settings".equals(d.getName()))return d;return null;});
                    if(settings==null)Thread.sleep(20);
                }
                if(settings==null)throw new AssertionError("Settings not opened");
                edt(()->null);
                System.out.printf("PERF settingsOpen iteration=%d durationMs=%d%n",iteration,(System.nanoTime()-open)/1_000_000);
                metric("settings"+iteration);
                JDialog current=settings;
                edt(()->{current.dispose();return null;});done.get(20,TimeUnit.SECONDS);
                fx(()->null);edt(()->null);
            }
            // 不调用 System.gc：观察正常空闲周期是否收缩已提交堆。
            metric("settingsClosed");
            Thread.sleep(Long.getLong("jlshell.performance.idleMillis", 35000L));
            metric("idleAfterSettings");
            fx(()->{context.getMainWindow().closeSwingWindow();return null;});
            context.close();Platform.exit();System.exit(0);
        } catch(Throwable e) {e.printStackTrace();System.exit(2);}
    }
}
