package com.jlshell.core.session;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/** A dedicated exec channel. Closing it must leave other session channels intact. */
public interface CommandChannel extends AutoCloseable {
    InputStream stdout();
    InputStream stderr();
    CompletableFuture<Integer> completion();
    void cancel();
    @Override void close();
}
