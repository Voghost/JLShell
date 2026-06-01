package com.jlshell.sysmon;

import java.util.List;

/**
 * Snapshot of system metrics at a single point in time.
 */
public record SystemMetrics(
        double cpuUsage,       // 0-100%
        int cpuCores,
        double cpuLoadAvg1m,
        long memTotal,         // bytes
        long memUsed,          // bytes
        long memCached,        // bytes
        long netBytesRecv,     // cumulative bytes since boot
        long netBytesSent,     // cumulative bytes since boot
        List<DiskInfo> disks,
        long timestamp         // epoch millis
) {
    public double memUsagePercent() {
        return memTotal > 0 ? (memUsed * 100.0 / memTotal) : 0;
    }

    public static record DiskInfo(String mount, long total, long used) {
        public double usagePercent() {
            return total > 0 ? (used * 100.0 / total) : 0;
        }
    }
}
