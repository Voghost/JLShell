package com.jlshell.ui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

class ExceptionMessagesTest {
    @org.junit.jupiter.api.Test void findsClassifiedReasonInsideAsyncWrappers() {
        var failure = new java.util.concurrent.CompletionException(new com.jlshell.core.exception.ConnectionException(
                "connect", new java.net.ConnectException(), com.jlshell.core.exception.ConnectionException.Reason.REFUSED));
        org.junit.jupiter.api.Assertions.assertEquals(com.jlshell.core.exception.ConnectionException.Reason.REFUSED,
                ExceptionMessages.connectionReason(failure));
        org.junit.jupiter.api.Assertions.assertEquals(com.jlshell.core.exception.ConnectionException.Reason.UNKNOWN,
                ExceptionMessages.connectionReason(new IllegalStateException("route failed")));
    }

    @Test
    void unwrapsNestedAsyncFailuresWithoutExposingWrapperTypes() {
        Throwable failure = new CompletionException(new CompletionException(
                new ExecutionException(new IllegalStateException("设备身份需要重新注册"))));

        assertEquals("设备身份需要重新注册", ExceptionMessages.userMessage(failure));
    }

    @Test
    void providesFallbackForFailuresWithoutMessages() {
        assertEquals("未知错误", ExceptionMessages.userMessage(new IllegalStateException()));
        assertEquals("未知错误", ExceptionMessages.userMessage(null));
    }
}
