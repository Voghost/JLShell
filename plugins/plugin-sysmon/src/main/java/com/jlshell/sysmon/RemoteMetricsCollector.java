package com.jlshell.sysmon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.capability.CommandExecutor;
import com.jlshell.plugin.api.model.CommandOutput;

/**
 * Collects remote system metrics via SSH command execution.
 * Parses standard Unix command output for Linux and macOS.
 */
public class RemoteMetricsCollector {

    private final SshSessionContext ssh;
    private String remoteOs; // "Linux" or "Darwin", cached after first detection

    public RemoteMetricsCollector(SshSessionContext ssh) {
        this.ssh = ssh;
    }

    public CompletableFuture<SystemMetrics> collect() {
        CommandExecutor exec = ssh.commandExecutor();
        if (remoteOs == null) {
            return exec.execute("uname -s", Duration.ofSeconds(10))
                    .thenCompose(output -> {
                        remoteOs = output.stdout().trim();
                        return collectInternal(exec);
                    });
        }
        return collectInternal(exec);
    }

    private CompletableFuture<SystemMetrics> collectInternal(CommandExecutor exec) {
        // Run all commands in parallel, then combine results
        CompletableFuture<String> cpuFuture = exec.execute(cpuCommand()).thenApply(CommandOutput::stdout);
        CompletableFuture<String> memFuture = exec.execute(memCommand()).thenApply(CommandOutput::stdout);
        CompletableFuture<String> netFuture = exec.execute(netCommand()).thenApply(CommandOutput::stdout);
        CompletableFuture<String> diskFuture = exec.execute("df -k 2>/dev/null || df -k").thenApply(CommandOutput::stdout);

        return CompletableFuture.allOf(cpuFuture, memFuture, netFuture, diskFuture)
                .thenApply(v -> {
                    double cpuUsage = parseCpu(cpuFuture.join());
                    int cpuCores = parseCpuCores(cpuFuture.join());
                    double loadAvg = parseLoadAvg(cpuFuture.join());

                    long[] mem = parseMem(memFuture.join());
                    long memTotal = mem[0], memUsed = mem[1], memCached = mem[2];

                    long[] net = parseNet(netFuture.join());
                    long netRecv = net[0], netSent = net[1];

                    List<SystemMetrics.DiskInfo> disks = parseDisk(diskFuture.join());

                    return new SystemMetrics(
                            cpuUsage, cpuCores, loadAvg,
                            memTotal, memUsed, memCached,
                            netRecv, netSent,
                            disks, System.currentTimeMillis()
                    );
                });
    }

    // ── Command selection by OS ──────────────────────────────────────────

    private String cpuCommand() {
        return "Linux".equals(remoteOs)
                ? "cat /proc/stat | head -1; nproc; cat /proc/loadavg"
                : "sysctl -n vm.loadavg; sysctl -n hw.logicalcpu; top -l 1 -n 0 | head -5";
    }

    private String memCommand() {
        return "Linux".equals(remoteOs)
                ? "free -b 2>/dev/null || cat /proc/meminfo"
                : "vm_stat; sysctl -n hw.memsize 2>/dev/null";
    }

    private String netCommand() {
        return "Linux".equals(remoteOs)
                ? "cat /proc/net/dev"
                : "netstat -ib 2>/dev/null";
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    private double parseCpu(String output) {
        if ("Linux".equals(remoteOs)) {
            // Parse /proc/stat first line: cpu  user nice system idle ...
            try {
                String[] parts = output.split("\n")[0].trim().split("\\s+");
                long idle = Long.parseLong(parts[4]);
                long total = 0;
                for (int i = 1; i < parts.length && i <= 8; i++) {
                    total += Long.parseLong(parts[i]);
                }
                return total > 0 ? ((total - idle) * 100.0 / total) : 0;
            } catch (Exception e) { return 0; }
        } else {
            // macOS: parse "CPU usage: X.X% user, Y.Y% sys, Z.Z% idle"
            Pattern p = Pattern.compile("(\\d+\\.?\\d*)%\\s+(?:user|sys|idle)");
            double used = 0;
            Matcher m = p.matcher(output);
            int count = 0;
            while (m.find() && count < 2) {
                used += Double.parseDouble(m.group(1));
                count++;
            }
            return Math.min(used, 100);
        }
    }

    private int parseCpuCores(String output) {
        if ("Linux".equals(remoteOs)) {
            try {
                return Integer.parseInt(output.split("\n")[1].trim());
            } catch (Exception e) { return 0; }
        } else {
            Pattern p = Pattern.compile("(\\d+)");
            Matcher m = p.matcher(output);
            if (m.find()) return Integer.parseInt(m.group(1));
            return 0;
        }
    }

    private double parseLoadAvg(String output) {
        if ("Linux".equals(remoteOs)) {
            try {
                return Double.parseDouble(output.split("\n")[2].trim().split("\\s+")[0]);
            } catch (Exception e) { return 0; }
        } else {
            // macOS sysctl output: "{ 2.14 1.89 1.73 }"
            Pattern p = Pattern.compile("\\{?\\s*([\\d.]+)");
            Matcher m = p.matcher(output);
            if (m.find()) return Double.parseDouble(m.group(1));
            return 0;
        }
    }

    private long[] parseMem(String output) {
        if ("Linux".equals(remoteOs)) {
            try {
                // free -b output: Mem: total used free shared buff/cache available
                String[] lines = output.split("\n");
                String[] parts = lines[1].trim().split("\\s+");
                long total = Long.parseLong(parts[1]);
                long used = Long.parseLong(parts[2]);
                long cached = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                return new long[]{total, used, cached};
            } catch (Exception e) { return new long[]{0, 0, 0}; }
        } else {
            // macOS vm_stat — page counts * page size
            try {
                long pageSize = 4096; // default
                long free = 0, active = 0, inactive = 0, wired = 0, total = 0;
                for (String line : output.split("\n")) {
                    if (line.contains("Pages free:")) free = parseVmStatNum(line);
                    else if (line.contains("Pages active:")) active = parseVmStatNum(line);
                    else if (line.contains("Pages inactive:")) inactive = parseVmStatNum(line);
                    else if (line.contains("Pages wired down:")) wired = parseVmStatNum(line);
                    else if (line.startsWith("hw.memsize")) total = parseVmStatNum(line);
                }
                if (total == 0) total = (free + active + inactive + wired) * pageSize;
                long used = (active + wired) * pageSize;
                long cached = inactive * pageSize;
                return new long[]{total, used, cached};
            } catch (Exception e) { return new long[]{0, 0, 0}; }
        }
    }

    private long parseVmStatNum(String line) {
        try {
            String num = line.replaceAll("[^\\d]", "").trim();
            return Long.parseLong(num);
        } catch (Exception e) { return 0; }
    }

    private long[] parseNet(String output) {
        long recv = 0, sent = 0;
        if ("Linux".equals(remoteOs)) {
            // /proc/net/dev: iface: recv_bytes ... sent_bytes ...
            for (String line : output.split("\n")) {
                if (line.contains(":")) {
                    String[] parts = line.split(":")[1].trim().split("\\s+");
                    if (parts.length >= 10) {
                        recv += Long.parseLong(parts[0]);
                        sent += Long.parseLong(parts[8]);
                    }
                }
            }
        } else {
            // macOS netstat -ib: Ibytes, Obytes columns
            for (String line : output.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 10) {
                    try {
                        recv += Long.parseLong(parts[6]);
                        sent += Long.parseLong(parts[9]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new long[]{recv, sent};
    }

    private List<SystemMetrics.DiskInfo> parseDisk(String output) {
        List<SystemMetrics.DiskInfo> disks = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("/") || line.startsWith("Filesystem")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    try {
                        long total = Long.parseLong(parts[1]) * 1024;
                        long used = Long.parseLong(parts[2]) * 1024;
                        String mount = parts[5];
                        disks.add(new SystemMetrics.DiskInfo(mount, total, used));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return disks;
    }
}
