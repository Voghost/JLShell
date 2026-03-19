package com.jlshell.sftp.model;

/**
 * 传输进度快照。
 */
public record TransferProgress(
        TransferDirection direction,
        String source,
        String target,
        long transferredBytes,
        long totalBytes
) {

    public double progressRatio() {
        if (totalBytes <= 0) {
            return 0D;
        }
        return (double) transferredBytes / totalBytes;
    }
}
