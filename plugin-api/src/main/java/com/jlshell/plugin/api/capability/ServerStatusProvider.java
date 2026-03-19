package com.jlshell.plugin.api.capability;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.model.CpuStatus;
import com.jlshell.plugin.api.model.DiskStatus;
import com.jlshell.plugin.api.model.MemoryStatus;
import com.jlshell.plugin.api.model.ProcessInfo;

public interface ServerStatusProvider {

    CompletableFuture<CpuStatus> cpuStatus();

    CompletableFuture<MemoryStatus> memoryStatus();

    CompletableFuture<List<DiskStatus>> diskStatus();

    CompletableFuture<List<ProcessInfo>> topProcesses(int limit);
}
