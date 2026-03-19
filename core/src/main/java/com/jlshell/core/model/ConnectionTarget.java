package com.jlshell.core.model;

import java.time.Duration;

/**
 * SSH 目标主机信息。
 */
public record ConnectionTarget(
        String host,
        int port,
        String username,
        Duration connectTimeout,
        Duration readTimeout
) {

    public ConnectionTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
    }
}
