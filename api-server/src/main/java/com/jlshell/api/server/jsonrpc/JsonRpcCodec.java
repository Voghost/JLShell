package com.jlshell.api.server.jsonrpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** JSON-RPC 2.0 编解码 + 错误码常量。 */
public final class JsonRpcCodec {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HOST_ERROR = -32000;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private JsonRpcCodec() {}

    public static JsonRpcRequest parse(String body) {
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        String jsonrpc = obj.has("jsonrpc") ? obj.get("jsonrpc").getAsString() : null;
        Object id = obj.has("id") && !obj.get("id").isJsonNull() ? gsonId(obj.get("id")) : null;
        String method = obj.has("method") ? obj.get("method").getAsString() : null;
        JsonElement params = obj.has("params") ? obj.get("params") : null;
        return new JsonRpcRequest(jsonrpc, id, method, params);
    }

    public static String encode(JsonRpcResponse resp) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (resp.id() != null) o.add("id", idToJson(resp.id()));
        else o.add("id", com.google.gson.JsonNull.INSTANCE);
        if (resp.error() != null) {
            JsonObject e = new JsonObject();
            e.addProperty("code", resp.error().code());
            e.addProperty("message", resp.error().message());
            if (resp.error().data() != null) e.add("data", resp.error().data());
            o.add("error", e);
        } else {
            o.add("result", resp.result() == null ? com.google.gson.JsonNull.INSTANCE : resp.result());
        }
        return GSON.toJson(o);
    }

    private static Object gsonId(JsonElement el) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isString()) return p.getAsString();
        }
        return el.toString(); // 保留原始
    }

    private static JsonElement idToJson(Object id) {
        if (id instanceof Number n) return new JsonPrimitive(n);
        if (id instanceof String s) return new JsonPrimitive(s);
        return new JsonPrimitive(String.valueOf(id));
    }
}
