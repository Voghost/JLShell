package com.jlshell.plugin.api.model;

import java.time.Instant;

public record RemoteFile(
        String name,
        String path,
        long size,
        boolean isDirectory,
        String permissions,
        Instant lastModified
) {}
