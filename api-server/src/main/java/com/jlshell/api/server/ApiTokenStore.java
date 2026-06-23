package com.jlshell.api.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ~/.jlshell/api.token 的读写。POSIX chmod 600；Windows 走默认（当前用户）。 */
public final class ApiTokenStore {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenStore.class);

    private ApiTokenStore() {}

    public static String loadOrCreate() {
        Path home = resolveHome();
        Path file = home.resolve("api.token");
        try {
            Files.createDirectories(home);
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String token = Base64.getEncoder().encodeToString(bytes);
            if (isPosix(home)) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                // 先创建带权限的空文件
                Files.createFile(file, PosixFilePermissions.asFileAttribute(perms));
                // 再写入内容
                Files.writeString(file, token);
            } else {
                Files.writeString(file, token, java.nio.file.StandardOpenOption.CREATE_NEW);
                // Windows: best-effort 限制到当前用户（posixAttribute 不可用，留作后续）
            }
            log.info("Created API token at {}", file);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load/create API token at " + file, e);
        }
    }

    private static Path resolveHome() {
        String override = System.getProperty("jlshell.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".jlshell");
    }

    private static boolean isPosix(Path p) {
        try { return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Exception e) { return false; }
    }
}