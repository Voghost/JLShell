package com.jlshell.sftp.model;

import java.time.Instant;

/**
 * 远程文件条目视图。
 */
public record RemoteFileEntry(
        String path,
        String name,
        RemoteFileType type,
        long size,
        String permissionString,
        Instant modifiedAt,
        Integer uid,
        Integer gid
) {

    public boolean isDirectory() {
        return type == RemoteFileType.DIRECTORY;
    }

    public boolean isFile() {
        return type == RemoteFileType.FILE;
    }
}
