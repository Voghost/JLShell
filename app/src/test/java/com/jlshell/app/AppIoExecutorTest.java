package com.jlshell.app;

import com.jlshell.ui.support.UiThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the real host executor in an isolated profile and JVM. */
class AppIoExecutorTest {
    @TempDir Path temporary;

    @Test
    void blockingOperationsUseVirtualThreadsAndKeepEdtResponsive() throws Exception {
        Path log = temporary.resolve("io.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + temporary,
                "-Djlshell.ui.preview=true", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("VIRTUAL_IO_READY"), () -> read(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception error) { return error.toString(); }
    }

    public static class Probe {
        public static void main(String[] args) {
            try (AppContext context = UiThread.supplyAsync(AppContext::new).get(20, TimeUnit.SECONDS)) {
                var field = AppContext.class.getDeclaredField("executor");
                field.setAccessible(true);
                ExecutorService executor = (ExecutorService) field.get(context);
                CountDownLatch started = new CountDownLatch(128);
                CountDownLatch release = new CountDownLatch(1);
                try {
                    for (int index = 0; index < 128; index++) {
                        executor.submit(() -> {
                            assertTrue(Thread.currentThread().isVirtual());
                            started.countDown();
                            release.await();
                            return null;
                        });
                    }
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    UiThread.supplyAsync(() -> true).get(1, TimeUnit.SECONDS);
                    System.out.println("VIRTUAL_IO_READY");
                } finally {
                    release.countDown();
                }
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(2);
            }
            System.exit(0);
        }
    }
}
