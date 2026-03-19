package com.jlshell.plugin.api.model;

public record DiskStatus(String mountPoint, String device, long totalBytes, long usedBytes, long freeBytes) {}
