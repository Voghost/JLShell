package com.jlshell.ui.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 本地文件条目。
 */
public record LocalFileEntry(
        Path path,
        String name,
        boolean directory,
        long size,
        Instant modifiedAt
) {
}
