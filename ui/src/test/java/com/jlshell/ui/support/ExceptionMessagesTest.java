package com.jlshell.ui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

class ExceptionMessagesTest {

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
