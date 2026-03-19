package com.jlshell.plugin.api.model;

public record MemoryStatus(long totalBytes, long usedBytes, long freeBytes, long cachedBytes) {}
