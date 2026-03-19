package com.jlshell.core.model;

import java.time.Duration;

/**
 * 单次命令执行结果。
 */
public record CommandResult(
        String command,
        Integer exitCode,
        String stdout,
        String stderr,
        Duration duration
) {
}
