package com.jlshell.api.server.jsonrpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcCodecTest {
    @Test
    void parsesRequestWithParams() {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "session.connect");
        JsonObject params = new JsonObject(); params.addProperty("connectionId", "c1");
        body.add("params", params);
        JsonRpcRequest req = JsonRpcCodec.parse(body.toString());
        assertThat(req.method()).isEqualTo("session.connect");
        assertThat(req.params().getAsJsonObject().get("connectionId").getAsString()).isEqualTo("c1");
    }

    @Test
    void encodesSuccessResponse() {
        JsonRpcResponse resp = JsonRpcResponse.ok(1, new JsonPrimitive("ok"));
        String json = JsonRpcCodec.encode(resp);
        assertThat(json).contains("\"result\":\"ok\"").contains("\"id\":1");
    }

    @Test
    void encodesErrorResponse() {
        JsonRpcResponse resp = JsonRpcResponse.err(2, JsonRpcError.of(-32601, "nope"));
        String json = JsonRpcCodec.encode(resp);
        assertThat(json).contains("\"error\"").contains("-32601").contains("nope");
    }
}
