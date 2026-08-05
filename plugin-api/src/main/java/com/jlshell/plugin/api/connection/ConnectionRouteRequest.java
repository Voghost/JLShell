package com.jlshell.plugin.api.connection;

import java.util.Objects;

/**
 * 连接建立前提供给 Program 插件的非敏感连接信息。
 *
 * <p>该对象不包含密码、私钥、跳板机配置或其他凭据。插件只能据此决定是否为本次连接
 * 建立一个本地回环路由。</p>
 */
public record ConnectionRouteRequest(
        String connectionId,
        String projectId,
        String displayName,
        String host,
        int port,
        String username
) {

    public ConnectionRouteRequest {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(username, "username");
        if (host.isBlank() || username.isBlank()) {
            throw new IllegalArgumentException("host and username must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
