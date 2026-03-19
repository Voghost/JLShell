package com.jlshell.plugin.api.capability;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.model.CommandOutput;

public interface CommandExecutor {

    CompletableFuture<CommandOutput> execute(String command);

    CompletableFuture<CommandOutput> execute(String command, Duration timeout);
}
