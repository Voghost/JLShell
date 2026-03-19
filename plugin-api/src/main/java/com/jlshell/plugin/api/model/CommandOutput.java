package com.jlshell.plugin.api.model;

public record CommandOutput(String stdout, String stderr, int exitCode) {}
