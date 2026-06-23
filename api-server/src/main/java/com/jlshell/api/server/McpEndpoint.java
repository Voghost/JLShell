package com.jlshell.api.server;

/**
 * MCP（Model Context Protocol）端点占位。
 *
 * <p>本次不实现。后续 MCP server（Streamable HTTP / stdio）应：
 * <ol>
 *   <li>复用 {@code ApiServer} 的 {@code MethodDispatcher} 调度能力 method；</li>
 *   <li>用 {@code capability.list} 生成 MCP tools 清单（name=pluginId.capability，inputSchema 透传）；</li>
 *   <li>把 MCP tool call 映射成 {@code capability.invoke}。</li>
 * </ol>
 * 留作独立 spec。
 */
public final class McpEndpoint {
    private McpEndpoint() {}
}
