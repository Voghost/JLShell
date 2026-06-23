package com.jlshell.api.server;

/** API server 配置。port=0 表示自动选空闲端口。 */
public record ApiServerConfig(int port, String token, boolean enabled) {}
