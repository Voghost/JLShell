package com.jlshell.sftp.model;

import java.util.List;

/**
 * 远程目录浏览结果。
 */
public record RemoteDirectoryListing(
        String canonicalPath,
        List<RemoteFileEntry> entries
) {
}
