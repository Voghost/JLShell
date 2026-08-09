package com.jlshell.ui.support;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Extracts a stable user-facing message from nested asynchronous failures. */
public final class ExceptionMessages {

    private ExceptionMessages() { }

    public static String userMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof CompletionException
                || current instanceof ExecutionException
                || current.getMessage() == null
                || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }
}
