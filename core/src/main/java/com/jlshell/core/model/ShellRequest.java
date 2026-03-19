package com.jlshell.core.model;

import java.util.Map;

/**
 * 交互式 Shell 打开参数。
 */
public record ShellRequest(
        String terminalType,
        TerminalSize terminalSize,
        Map<String, String> environment
) {

    public ShellRequest {
        terminalType = terminalType == null || terminalType.isBlank() ? "xterm-256color" : terminalType;
        terminalSize = terminalSize == null ? TerminalSize.defaultSize() : terminalSize;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
