package com.jlshell.core.exception;

public class SessionOperationException extends CoreException {

    public SessionOperationException(String message) {
        super(message);
    }

    public SessionOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
