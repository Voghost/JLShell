package com.jlshell.plugin.api.connection;

import java.util.concurrent.CompletableFuture;

/** Program 插件对 SSH 连接建立前阶段的贡献。 */
public interface ProgramConnectionRouteContribution {

    /** 是否接管该连接。多个插件同时接管同一连接时，宿主会拒绝连接。 */
    boolean supports(ConnectionRouteRequest request);

    /**
     * 异步准备本地回环路由，例如签发短期票据并启动 Connector 隧道。
     * 返回的租约由宿主在失败或会话关闭时释放。
     */
    CompletableFuture<ConnectionRoute> route(ConnectionRouteRequest request);
}
