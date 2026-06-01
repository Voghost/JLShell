package com.jlshell.sysmon;

import java.util.ArrayList;
import java.util.List;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

/**
 * Collects local system metrics using OSHI.
 */
public class MetricsCollector {

    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hal;
    private final OperatingSystem os;
    private long[] prevCpuTicks;
    private long prevNetBytesRecv;
    private long prevNetBytesSent;

    public MetricsCollector() {
        this.systemInfo = new SystemInfo();
        this.hal = systemInfo.getHardware();
        this.os = systemInfo.getOperatingSystem();
        this.prevCpuTicks = hal.getProcessor().getSystemCpuLoadTicks();
        this.prevNetBytesRecv = -1;
        this.prevNetBytesSent = -1;
    }

    public SystemMetrics collect() {
        CentralProcessor cpu = hal.getProcessor();

        // CPU
        double cpuUsage = cpu.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100.0;
        prevCpuTicks = cpu.getSystemCpuLoadTicks();
        int cpuCores = cpu.getLogicalProcessorCount();
        double loadAvg = cpu.getSystemLoadAverage(1)[0];
        if (loadAvg < 0) loadAvg = 0;

        // Memory
        oshi.hardware.GlobalMemory mem = hal.getMemory();
        long memTotal = mem.getTotal();
        long memAvailable = mem.getAvailable();
        long memUsed = memTotal - memAvailable;
        long memCached = mem.getPageSize() > 0 ? (memTotal - memAvailable - memUsed) : 0;

        // Network — cumulative since boot
        long netBytesRecv = 0;
        long netBytesSent = 0;
        for (var nic : hal.getNetworkIFs()) {
            if (!nic.getName().equals("lo")) {
                netBytesRecv += nic.getBytesRecv();
                netBytesSent += nic.getBytesSent();
            }
        }
        // Delta from previous sample
        long deltaRecv = prevNetBytesRecv < 0 ? 0 : (netBytesRecv - prevNetBytesRecv);
        long deltaSent = prevNetBytesSent < 0 ? 0 : (netBytesSent - prevNetBytesSent);
        prevNetBytesRecv = netBytesRecv;
        prevNetBytesSent = netBytesSent;

        // Disk
        FileSystem fs = os.getFileSystem();
        List<SystemMetrics.DiskInfo> disks = new ArrayList<>();
        for (OSFileStore store : fs.getFileStores()) {
            long total = store.getTotalSpace();
            long used = total - store.getUsableSpace();
            disks.add(new SystemMetrics.DiskInfo(store.getMount(), total, used));
        }

        return new SystemMetrics(
                cpuUsage, cpuCores, loadAvg,
                memTotal, memUsed, memCached,
                deltaRecv, deltaSent,
                disks,
                System.currentTimeMillis()
        );
    }
}
