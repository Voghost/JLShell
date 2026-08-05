package com.jlshell.plugin.api.connection;

import java.util.Objects;

/**
 * Program 插件为一次连接创建的本地回环路由及其资源租约。
 *
 * <p>宿主仅接受 {@code 127.0.0.1} 或 {@code ::1}，以避免插件将保存的 SSH 连接
 * 静默改写到另一个远程地址。连接失败或会话关闭时，宿主一定会关闭 {@link #lease()}。</p>
 */
public record ConnectionRoute(String host, int port, AutoCloseable lease) {

    private static final AutoCloseable NOOP_LEASE = () -> { };

    public ConnectionRoute {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(lease, "lease");
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException("connection routes must use a loopback address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public static ConnectionRoute loopback(String host, int port, AutoCloseable lease) {
        return new ConnectionRoute(host, port, lease == null ? NOOP_LEASE : lease);
    }
}
