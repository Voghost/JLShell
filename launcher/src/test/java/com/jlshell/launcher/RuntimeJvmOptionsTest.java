package com.jlshell.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeJvmOptionsTest {
    @TempDir Path temporary;

    @Test void idleVmReturnsHeapAfterAnAllocationBurstWithoutExplicitGc() throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(RuntimeJvmOptions.defaults());
        // 测试只缩短空闲周期，不修改生产的堆大小或回收策略。
        command.add("-XX:G1PeriodicGCInterval=250");
        command.add("-cp"); command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        Path log = temporary.resolve("memory.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("HEAP_RETURNED"), () -> read(log));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }

    public static class Probe {
        static volatile byte[][] retained;
        public static void main(String[] args) throws Exception {
            retained = new byte[128][];
            for (int i = 0; i < retained.length; i++) retained[i] = new byte[512 * 1024];
            long peak = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted();
            retained = null;
            for (int i = 0; i < 150; i++) {
                Thread.sleep(100);
                long committed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted();
                if (committed < peak * .7) {
                    System.out.println("HEAP_RETURNED " + peak + " -> " + committed); return;
                }
            }
            throw new AssertionError("Idle heap did not shrink from " + peak);
        }
    }
}
