package com.jlshell.ui.service.importer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 Xshell 会话文件 (.xsh)。
 *
 * .xsh 是 INI 格式，但无段名（或只有 [Session] 段），直接 key=value。
 * 字段：Host / Port / UserName / AuthType (0=Password,1=PublicKey) / PrivateKeyPath。
 * 密码字段加密，这里跳过。会话名 = 文件名（去 .xsh）。
 *
 * 支持单文件 + 目录批量。编码先试 UTF-8，失败回退 GBK（中文版 Xshell 常用 GBK）。
 */
public class XshellXshParser {

    private static final Logger log = LoggerFactory.getLogger(XshellXshParser.class);
    private static final Charset GBK = Charset.forName("GBK");

    private final String projectId;

    public XshellXshParser(String projectId) {
        this.projectId = projectId;
    }

    public List<ConnectionFormData> parseFile(Path xshFile) throws IOException {
        Map<String, String> fields = readIni(xshFile);
        String host = fields.get("Host");
        if (host == null || host.isBlank()) return List.of();

        String name = stripXshExtension(xshFile.getFileName().toString());
        int port = parseIntOrDefault(fields.get("Port"), 22);
        String username = fields.getOrDefault("UserName", "");
        AuthenticationType authType = parseAuthType(fields.get("AuthType"));
        String privateKeyPath = fields.get("PrivateKeyPath");

        return List.of(new ConnectionFormData(
                null, name, host, port, username,
                authType, "", privateKeyPath != null ? privateKeyPath : "", "",
                HostKeyVerificationMode.STRICT, "", "", false, projectId,
                ConnectionType.SSH, null, null, null
        ));
    }

    public List<ConnectionFormData> parseDirectory(Path dir) throws IOException {
        List<ConnectionFormData> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xsh"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    result.addAll(parseFile(file));
                } catch (IOException e) {
                    log.warn("Skip unreadable .xsh file {}: {}", file, e.getMessage());
                }
            }
        }
        log.info("Parsed {} Xshell sessions from {}", result.size(), dir);
        return result;
    }

    private static Map<String, String> readIni(Path file) throws IOException {
        List<String> lines = readLinesWithFallback(file);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            fields.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return fields;
    }

    private static List<String> readLinesWithFallback(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return new String(bytes, GBK).lines().toList();
        }
    }

    private static String stripXshExtension(String fileName) {
        if (fileName.toLowerCase().endsWith(".xsh")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static AuthenticationType parseAuthType(String value) {
        if (value == null) return AuthenticationType.PASSWORD;
        return switch (value.trim()) {
            case "1", "2" -> AuthenticationType.PRIVATE_KEY;
            default -> AuthenticationType.PASSWORD;
        };
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
