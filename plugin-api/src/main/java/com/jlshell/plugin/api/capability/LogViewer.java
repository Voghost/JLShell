package com.jlshell.plugin.api.capability;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public interface LogViewer {

    CompletableFuture<List<String>> tail(String filePath, int lines);

    CompletableFuture<Void> follow(String filePath, Consumer<String> lineConsumer, AtomicBoolean stop);
}
