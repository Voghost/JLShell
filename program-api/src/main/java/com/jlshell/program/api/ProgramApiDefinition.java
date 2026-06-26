package com.jlshell.program.api;

import com.google.gson.JsonObject;

public record ProgramApiDefinition(
        String method,
        String description,
        boolean requiresSession,
        JsonObject inputSchema,
        String resultHint,
        String paramsExample
) {}
