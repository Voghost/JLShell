package com.jlshell.sftp.model;

import java.nio.file.Path;

/**
 * 文件传输请求。
 */
public record TransferRequest(
        Path localPath,
        String remotePath,
        TransferResumeMode resumeMode,
        int bufferSize
) {

    public TransferRequest {
        if (localPath == null) {
            throw new IllegalArgumentException("localPath must not be null");
        }
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("remotePath must not be blank");
        }
        resumeMode = resumeMode == null ? TransferResumeMode.OVERWRITE : resumeMode;
        bufferSize = bufferSize <= 0 ? 64 * 1024 : bufferSize;
    }
}
