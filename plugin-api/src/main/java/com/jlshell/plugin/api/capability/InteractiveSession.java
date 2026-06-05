package com.jlshell.plugin.api.capability;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * An interactive SSH session that supports reading output and writing input
 * in a multi-step fashion. Obtained via {@link InteractiveCommandExecutor#start(String)}.
 *
 * <p>Typical usage:
 * <pre>
 * InteractiveSession session = executor.start("./decode").join();
 * String initialOutput = session.readOutput();
 * // ... user reviews output, obtains a code ...
 * session.writeInput(randomCode + "\n");
 * String finalOutput = session.readUntil("---", Duration.ofSeconds(10)).join();
 * session.close();
 * </pre>
 */
public interface InteractiveSession extends AutoCloseable {

    /**
     * Read all output that has been produced so far by the remote command.
     * Returns whatever is currently available without blocking.
     *
     * @return the accumulated output text (may be empty if nothing has been produced yet)
     */
    String readOutput();

    /**
     * Write a line of input to the remote command's stdin.
     *
     * @param input the text to send (typically includes a newline)
     */
    void writeInput(String input);

    /**
     * Wait until the output contains the specified prompt string, then return
     * all accumulated output up to and including that prompt.
     *
     * @param prompt a substring to wait for in the output (e.g. "请输入")
     * @param timeout maximum duration to wait
     * @return a future that completes with the output text once the prompt appears,
     *         or fails with a timeout exception
     */
    CompletableFuture<String> readUntil(String prompt, Duration timeout);

    /**
     * Close the interactive session, terminating the remote command.
     */
    @Override
    void close();
}