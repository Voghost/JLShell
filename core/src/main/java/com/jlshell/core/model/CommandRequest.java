package com.jlshell.core.model;

import java.time.Duration;
import java.util.Map;

/**
 * 远程命令执行请求。
 * 该模型同时服务于“后台命令执行”和“需要 PTY 的半交互命令”两类场景。
 */
public record CommandRequest(
        String command,
        Duration timeout,
        boolean allocatePty,
        Map<String, String> environment
) {

    public CommandRequest {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
