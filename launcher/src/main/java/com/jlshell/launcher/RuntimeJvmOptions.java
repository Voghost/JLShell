package com.jlshell.launcher;

import java.util.List;

/** 小初始堆和空闲期并发回收；保留会话与插件可用的最大堆空间。 */
final class RuntimeJvmOptions {
    private RuntimeJvmOptions() {}

    static List<String> defaults() {
        return List.of("-Xms32m", "-Xmx512m", "-XX:+UseG1GC",
                "-XX:+ExplicitGCInvokesConcurrent", "-XX:G1PeriodicGCInterval=30000",
                "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40");
    }
}
