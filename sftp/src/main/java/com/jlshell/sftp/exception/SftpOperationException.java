package com.jlshell.sftp.exception;

/**
 * SFTP 操作异常。
 */
public class SftpOperationException extends RuntimeException {

    public SftpOperationException(String message) {
        super(message);
    }

    public SftpOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
