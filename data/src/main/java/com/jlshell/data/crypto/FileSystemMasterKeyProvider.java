package com.jlshell.data.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.jlshell.data.config.CredentialEncryptionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件系统主密钥提供器。
 * 若未显式配置 base64 密钥，则会在本地生成并持久化一个 AES-256 密钥。
 */
public class FileSystemMasterKeyProvider implements MasterKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMasterKeyProvider.class);

    private final CredentialEncryptionProperties properties;

    public FileSystemMasterKeyProvider(CredentialEncryptionProperties properties) {
        this.properties = properties;
    }

    @Override
    public SecretKey loadOrCreate() {
        if (properties.getMasterKeyBase64() != null && !properties.getMasterKeyBase64().isBlank()) {
            return decode(properties.getMasterKeyBase64().trim());
        }

        Path keyFile = properties.getMasterKeyFile();
        try {
            if (Files.exists(keyFile)) {
                return decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            }

            Files.createDirectories(keyFile.getParent());
            SecretKey secretKey = generate();
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(secretKey.getEncoded()), StandardCharsets.UTF_8);
            trySetPosixPermission(keyFile);
            log.info("Generated local AES master key at {}", keyFile);
            return secretKey;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load or create master key at " + keyFile, exception);
        }
    }

    private SecretKey generate() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            return keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("AES algorithm is not available", exception);
        }
    }

    private SecretKey decode(String base64) {
        byte[] key = Base64.getDecoder().decode(base64);
        return new SecretKeySpec(key, "AES");
    }

    private void trySetPosixPermission(Path keyFile) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(keyFile, permissions);
        } catch (UnsupportedOperationException | IOException exception) {
            log.debug("Skipping POSIX permission update for {}", keyFile, exception);
        }
    }
}
