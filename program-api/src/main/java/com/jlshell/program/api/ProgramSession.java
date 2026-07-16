package com.jlshell.program.api;

/** 面向 Program API 的稳定会话描述。 */
public record ProgramSession(
        String sessionId,
        String displayName,
        String host,
        int port,
        String user,
        String state
) {
}
