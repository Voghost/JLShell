package com.jlshell.ui.service.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 MobaXterm 会话导出 INI。
 *
 * MobaXterm 把会话存在 [SessionSettings\&lt;name&gt;] 段里，反斜杠是文件夹路径分隔符
 * （例如 [SessionSettings\Work\prod-server]）。每个段有 SshHost / SshPort / SshLogin /
 * SshAuthType / SshPrivateKeyFile / SshPassword 等字段。密码字段加密，这里跳过。
 *
 * 只解析 SSH 会话（段名以 SessionSettings 开头且有 SshHost 字段）。
 */
public class MobaXtermIniParser {

    private static final Logger log = LoggerFactory.getLogger(MobaXtermIniParser.class);
    private static final String SECTION_PREFIX = "[SessionSettings";
    private static final String SSH_HOST_KEY = "SshHost";

    private final String projectId;

    public MobaXtermIniParser(String projectId) {
        this.projectId = projectId;
    }

    public List<ConnectionFormData> parse(Path iniFile) throws IOException {
        List<String> lines = Files.readAllLines(iniFile, StandardCharsets.UTF_8);
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        String currentSection = null;
        Map<String, String> currentFields = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                if (currentSection != null && currentFields != null) {
                    sections.put(currentSection, currentFields);
                }
                currentSection = line;
                currentFields = new LinkedHashMap<>();
                continue;
            }
            if (currentFields == null) continue;

            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            currentFields.put(key, value);
        }
        if (currentSection != null && currentFields != null) {
            sections.put(currentSection, currentFields);
        }

        List<ConnectionFormData> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : sections.entrySet()) {
            String section = entry.getKey();
            Map<String, String> fields = entry.getValue();
            if (!section.startsWith(SECTION_PREFIX)) continue;
            if (!fields.containsKey(SSH_HOST_KEY)) continue;

            String name = extractSessionName(section);
            String host = fields.get(SSH_HOST_KEY);
            if (host == null || host.isBlank()) continue;

            int port = parseIntOrDefault(fields.get("SshPort"), 22);
            String username = fields.getOrDefault("SshLogin", "");
            AuthenticationType authType = parseAuthType(fields.get("SshAuthType"));
            String privateKeyPath = fields.get("SshPrivateKeyFile");

            result.add(new ConnectionFormData(
                    null, name, host, port, username,
                    authType, "", privateKeyPath != null ? privateKeyPath : "", "",
                    HostKeyVerificationMode.STRICT, "", "", false, projectId,
                    com.jlshell.core.model.ConnectionType.SSH, null, null, null
            ));
        }
        log.info("Parsed {} MobaXterm SSH sessions from {}", result.size(), iniFile);
        return result;
    }

    /**
     * 段名格式 [SessionSettings\Sub\Folder\Session] → 取最后一段 "Session"。
     * 没有反斜杠时取 [SessionSettings\X] 里的 X。
     */
    private static String extractSessionName(String section) {
        String inner = section.substring(SECTION_PREFIX.length(), section.length() - 1);
        if (inner.startsWith("\\")) inner = inner.substring(1);
        int lastSlash = inner.lastIndexOf('\\');
        return lastSlash >= 0 ? inner.substring(lastSlash + 1) : inner;
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
