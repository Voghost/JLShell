package com.jlshell.program.api;

import java.util.List;

import com.google.gson.JsonObject;

public final class ProgramApiCatalog {

    public static final String SESSION_CONNECT = "session.connect";
    public static final String SESSION_DISCONNECT = "session.disconnect";
    public static final String SESSION_LIST = "session.list";
    public static final String SESSION_INFO = "session.info";
    public static final String COMMAND_RUN = "command.run";
    public static final String CAPABILITY_LIST = "capability.list";
    public static final String CAPABILITY_INVOKE = "capability.invoke";
    public static final String API_TOKEN = "api.token";
    public static final String API_METHODS = "api.methods";
    public static final String ACCOUNT_STATUS = "account.status";

    private static final List<ProgramApiDefinition> DEFINITIONS = List.of(
            api(SESSION_CONNECT, "Open a saved connection by id.", false, "sessionId",
                    "{\"connectionId\":\"<connection-id>\"}", "connectionId"),
            api(SESSION_DISCONNECT, "Close an active session.", false, "ok",
                    "{\"sessionId\":\"<session-id>\"}", "sessionId"),
            api(SESSION_LIST, "List active sessions.", false, "[...]",
                    "{}", (String[]) null),
            api(SESSION_INFO, "Get one active session.", true, "{...}",
                    "{\"sessionId\":\"<session-id>\"}", "sessionId"),
            api(COMMAND_RUN, "Run a command in an active SSH session.", true, "{stdout,stderr,exitCode}",
                    "{\"sessionId\":\"<session-id>\",\"command\":\"pwd\",\"timeoutSec\":30}", "sessionId", "command"),
            api(CAPABILITY_LIST, "List plugin capabilities for a session or global scope.", false, "[...]",
                    "{\"sessionId\":\"<optional-session-id>\"}", (String[]) null),
            api(CAPABILITY_INVOKE, "Invoke a plugin capability.", false, "plugin result",
                    "{\"sessionId\":\"<optional-session-id>\",\"pluginId\":\"<plugin-id>\",\"capability\":\"<capability>\",\"args\":{}}",
                    "pluginId", "capability"),
            api(API_TOKEN, "Return current API token.", false, "token", "{}", (String[]) null),
            api(ACCOUNT_STATUS, "Return the non-sensitive host account session status.", false,
                    "{authenticated,username,...}", "{}", (String[]) null),
            api(API_METHODS, "List system API method names.", false, "[...]", "{}", (String[]) null)
    );

    private ProgramApiCatalog() {}

    public static List<ProgramApiDefinition> definitions() {
        return DEFINITIONS;
    }

    public static List<String> methodNames() {
        return DEFINITIONS.stream().map(ProgramApiDefinition::method).toList();
    }

    private static ProgramApiDefinition api(String method, String description, boolean requiresSession,
                                            String resultHint, String paramsExample, String... requiredFields) {
        return new ProgramApiDefinition(method, description, requiresSession,
                schema(requiredFields), resultHint, paramsExample);
    }

    private static JsonObject schema(String... requiredFields) {
        if (requiredFields == null || requiredFields.length == 0 || requiredFields[0] == null) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        for (String field : requiredFields) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            properties.add(field, prop);
            required.add(field);
        }
        root.add("properties", properties);
        root.add("required", required);
        return root;
    }
}
