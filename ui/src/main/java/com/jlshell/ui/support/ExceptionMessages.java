package com.jlshell.ui.support;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Extracts a stable user-facing message from nested asynchronous failures. */
public final class ExceptionMessages {

    private ExceptionMessages() { }

    public static com.jlshell.core.exception.ConnectionException.Reason connectionReason(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof com.jlshell.core.exception.ConnectionException connection
                    && connection.reason() != com.jlshell.core.exception.ConnectionException.Reason.UNKNOWN) return connection.reason();
        }
        return com.jlshell.core.exception.ConnectionException.Reason.UNKNOWN;
    }

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
