package com.jlshell.sftp.model;

/**
 * 断点续传策略。
 * 当前已预留接口，并实现了基础的按现有大小续传逻辑。
 */
public enum TransferResumeMode {
    OVERWRITE,
    RESUME_IF_POSSIBLE
}
