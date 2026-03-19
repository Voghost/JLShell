package com.jlshell.plugin.api.model;

public record ProcessInfo(int pid, String user, double cpuPercent, double memPercent, String command) {}
