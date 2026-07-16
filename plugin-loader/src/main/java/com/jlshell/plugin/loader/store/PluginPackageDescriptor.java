package com.jlshell.plugin.loader.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.plugin.api.PluginScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** 插件包内 {@code META-INF/jlshell-plugin.json} 的静态描述。 */
record PluginPackageDescriptor(
        int schemaVersion,
        String id,
        String version,
        PluginScope scope,
        String entrypoint,
        String displayName,
        String description,
        String author,
        String minHostVersion,
        String maxHostVersion
) {
    static final String PATH = "META-INF/jlshell-plugin.json";
    private static final Pattern ID_PATTERN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    static PluginPackageDescriptor parse(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes);
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("插件静态清单必须使用 UTF-8 编码", e);
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            PluginPackageDescriptor descriptor = new PluginPackageDescriptor(
                    requiredInt(root, "schemaVersion"),
                    requiredString(root, "id"),
                    requiredString(root, "version"),
                    PluginScope.valueOf(requiredString(root, "scope")),
                    requiredString(root, "entrypoint"),
                    requiredString(root, "displayName"),
                    requiredString(root, "description"),
                    requiredString(root, "author"),
                    optionalString(root, "minHostVersion"),
                    optionalString(root, "maxHostVersion"));
            descriptor.validate();
            return descriptor;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("插件静态清单 JSON 无效", e);
        }
    }

    void validateStoreIdentity(String expectedId, String expectedVersion, PluginScope expectedScope,
                               String expectedEntrypoint, String expectedMinHostVersion,
                               String expectedMaxHostVersion) throws IOException {
        if (!id.equals(expectedId) || !version.equals(expectedVersion) || scope != expectedScope) {
            throw new IOException("插件静态清单与商店 ID、版本或作用域不一致");
        }
        if (!entrypoint.equals(expectedEntrypoint)) {
            throw new IOException("插件静态清单入口类与商店声明不一致");
        }
        if (!Objects.equals(normalize(minHostVersion), normalize(expectedMinHostVersion))
                || !Objects.equals(normalize(maxHostVersion), normalize(expectedMaxHostVersion))) {
            throw new IOException("插件静态清单兼容范围与商店声明不一致");
        }
    }

    private void validate() throws IOException {
        if (schemaVersion != 1) {
            throw new IOException("不支持的插件静态清单版本：" + schemaVersion);
        }
        if (id.length() > 128 || !ID_PATTERN.matcher(id).matches()) {
            throw new IOException("插件静态清单 id 必须是小写反向域名");
        }
        parseSemVer(version, "version");
        if (!CLASS_PATTERN.matcher(entrypoint).matches()) {
            throw new IOException("插件静态清单 entrypoint 不是有效的 Java 类名");
        }
        if (displayName.length() > 100) {
            throw new IOException("插件静态清单 displayName 不能超过 100 个字符");
        }
        if (minHostVersion != null) parseSemVer(minHostVersion, "minHostVersion");
        if (maxHostVersion != null) parseSemVer(maxHostVersion, "maxHostVersion");
        if (minHostVersion != null && maxHostVersion != null
                && SemVer.parse(minHostVersion).compareTo(SemVer.parse(maxHostVersion)) > 0) {
            throw new IOException("插件静态清单兼容版本范围无效");
        }
    }

    private static void parseSemVer(String value, String field) throws IOException {
        try {
            SemVer.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("插件静态清单 " + field + " 必须符合 SemVer 2.0", e);
        }
    }

    private static int requiredInt(JsonObject root, String name) throws IOException {
        if (!root.has(name) || !root.get(name).isJsonPrimitive()
                || !root.getAsJsonPrimitive(name).isNumber()) {
            throw new IOException("插件静态清单缺少字段：" + name);
        }
        return root.get(name).getAsInt();
    }

    private static String requiredString(JsonObject root, String name) throws IOException {
        String value = optionalString(root, name);
        if (value == null) throw new IOException("插件静态清单缺少字段：" + name);
        return value;
    }

    private static String optionalString(JsonObject root, String name) throws IOException {
        if (!root.has(name) || root.get(name).isJsonNull()) return null;
        if (!root.get(name).isJsonPrimitive() || !root.getAsJsonPrimitive(name).isString()) {
            throw new IOException("插件静态清单字段类型错误：" + name);
        }
        String value = root.get(name).getAsString().strip();
        return value.isEmpty() ? null : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
