package com.jlshell.sftp.exception;

/**
 * 传输被用户取消时抛出。
 * 与 SftpOperationException 不同，取消不是错误，不需要向用户报告失败。
 */
public class TransferCancelledException extends RuntimeException {

    public TransferCancelledException() {
        super("Transfer cancelled");
    }
}
