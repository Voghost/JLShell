package com.jlshell.plugin.api.model;

public record CpuStatus(double usagePercent, int coreCount, double loadAverage1m) {}
