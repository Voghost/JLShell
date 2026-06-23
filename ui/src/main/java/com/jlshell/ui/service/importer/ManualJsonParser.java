package com.jlshell.ui.service.importer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析手动导入的 JSON 连接列表。
 *
 * Schema:
 * [
 *   {
 *     "name": "prod-server",
 *     "host": "1.2.3.4",
 *     "port": 22,
 *     "user": "root",
 *     "authType": "PASSWORD" | "PRIVATE_KEY",
 *     "privateKeyPath": "/path/to/key",
 *     "description": "optional"
 *   },
 *   ...
 * ]
 *
 * password / passphrase 可选——如果 JSON 里带了就一并保存（加密入库），
 * 没带则留空，用户后续在连接编辑里补。
 */
public class ManualJsonParser {

    private static final Logger log = LoggerFactory.getLogger(ManualJsonParser.class);

    private final String projectId;
    private final Gson gson = new Gson();

    public ManualJsonParser(String projectId) {
        this.projectId = projectId;
    }

    public List<ConnectionFormData> parse(Path jsonFile) throws IOException {
        try {
            String text = Files.readString(jsonFile, StandardCharsets.UTF_8);
            List<ConnectionFormData> result = parseJsonText(text);
            log.info("Parsed {} manual connections from {}", result.size(), jsonFile);
            return result;
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 直接从 JSON 文本字符串解析（用于粘贴导入）。
     */
    public List<ConnectionFormData> parseJsonText(String jsonText) {
        JsonElement root = JsonParser.parseString(jsonText);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("JSON root must be an array of connection objects");
        }
        JsonArray array = root.getAsJsonArray();
        List<ConnectionFormData> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            ConnectionFormData form = fromObject(item.getAsJsonObject());
            if (form != null) result.add(form);
        }
        return result;
    }

    private ConnectionFormData fromObject(JsonObject obj) {
        String name = optString(obj, "name", "unnamed");
        String host = optString(obj, "host", "");
        if (host.isBlank()) {
            log.warn("Skip entry without host: {}", obj);
            return null;
        }
        int port = optInt(obj, "port", 22);
        String user = optString(obj, "user", obj.has("username") ? obj.get("username").getAsString() : "");
        AuthenticationType authType = parseAuthType(optString(obj, "authType", "PASSWORD"));
        String privateKeyPath = optString(obj, "privateKeyPath", "");
        String description = optString(obj, "description", "");
        // 可选：JSON 可带 password / passphrase，导入时一并保存（加密入库）
        String password = optString(obj, "password", "");
        String passphrase = optString(obj, "passphrase", "");

        return new ConnectionFormData(
                null, name, host, port, user,
                authType, password, privateKeyPath, passphrase,
                HostKeyVerificationMode.STRICT, description, "", false, projectId,
                ConnectionType.SSH, null, null, null
        );
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            String s = obj.get(key).getAsString();
            return s == null ? fallback : s;
        }
        return fallback;
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static AuthenticationType parseAuthType(String value) {
        if (value == null) return AuthenticationType.PASSWORD;
        return switch (value.trim().toUpperCase()) {
            case "PRIVATE_KEY", "KEY", "PUBLICKEY" -> AuthenticationType.PRIVATE_KEY;
            default -> AuthenticationType.PASSWORD;
        };
    }

    /** TableView 逐行编辑用的 schema：把 List&lt;Map&gt; 转成 ConnectionFormData 列表 */
    public List<ConnectionFormData> fromRows(List<Map<String, String>> rows) {
        List<ConnectionFormData> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String host = row.getOrDefault("host", "");
            if (host.isBlank()) continue;
            result.add(new ConnectionFormData(
                    null,
                    row.getOrDefault("name", "unnamed"),
                    host,
                    parseInt(row.get("port"), 22),
                    row.getOrDefault("user", ""),
                    parseAuthType(row.get("authType")),
                    row.getOrDefault("password", ""),
                    row.getOrDefault("privateKeyPath", ""),
                    row.getOrDefault("passphrase", ""),
                    HostKeyVerificationMode.STRICT,
                    row.getOrDefault("description", ""),
                    "", false, projectId,
                    ConnectionType.SSH, null, null, null
            ));
        }
        return result;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
