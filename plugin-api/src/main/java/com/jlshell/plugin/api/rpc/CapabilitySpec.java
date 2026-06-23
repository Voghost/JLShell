package com.jlshell.plugin.api.rpc;

import com.google.gson.JsonObject;

/**
 * 能力规格。name 不含 pluginId（路由时由 host 拼接）。
 * inputSchema 为 JSON Schema，null 表示无参。初版 host 仅做参数存在性校验。
 */
public record CapabilitySpec(String name, String description,
                             JsonObject inputSchema, boolean requiresSession) {}
