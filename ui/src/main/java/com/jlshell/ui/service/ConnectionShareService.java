package com.jlshell.ui.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;

public class ConnectionShareService {

    public static final String PREFIX = "JLSHELL_CONNECTION_SHARE_V1:";
    public static final String SHARE_CODE_SEPARATOR = "\nJLSHELL_CONNECTION_SHARE_CODE:";
    private static final int VERSION = 1;
    private static final int ITERATIONS = 200_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom;
    private final Gson gson = new Gson();

    public ConnectionShareService() {
        this(new SecureRandom());
    }

    ConnectionShareService(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generateShareCode() {
        byte[] bytes = new byte[10];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String exportShareText(ConnectionFormData form, String shareCode, boolean includeShareCode) {
        validateShareCode(shareCode == null ? null : shareCode.toCharArray());
        String shareText = exportShareText(form, shareCode.toCharArray());
        return includeShareCode ? shareText + SHARE_CODE_SEPARATOR + shareCode.trim() : shareText;
    }

    public String exportShareText(ConnectionFormData form, char[] shareCode) {
        validateShareCode(shareCode);
        try {
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(IV_BYTES);
            SecretKeySpec key = deriveKey(shareCode, salt);

            ConnectionSharePayload secret = new ConnectionSharePayload(
                    nullToEmpty(form.password()),
                    nullToEmpty(form.passphrase())
            );
            byte[] encrypted = encrypt(gson.toJson(secret).getBytes(StandardCharsets.UTF_8), key, iv);

            ConnectionShareEnvelope envelope = new ConnectionShareEnvelope(
                    VERSION,
                    nullToEmpty(form.displayName()),
                    nullToEmpty(form.host()),
                    form.port(),
                    nullToEmpty(form.username()),
                    authType(form).name(),
                    nullToEmpty(form.privateKeyPath()),
                    nullToEmpty(form.description()),
                    nullToEmpty(form.defaultRemotePath()),
                    false,
                    "PBKDF2WithHmacSHA256",
                    ITERATIONS,
                    "AES-256-GCM",
                    encode(salt),
                    encode(iv),
                    encode(encrypted)
            );
            return PREFIX + encode(gson.toJson(envelope).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt connection share", e);
        }
    }

    public ConnectionFormData importShareText(String text, String projectId) {
        ConnectionShareEnvelope envelope = validateEnvelope(parseEnvelope(text));
        String embeddedShareCode = extractShareCode(text);
        if (embeddedShareCode == null || embeddedShareCode.isBlank()) {
            throw new IllegalArgumentException("Share text requires a share code");
        }
        return importShareText(text, embeddedShareCode.toCharArray(), projectId);
    }

    public ConnectionFormData importShareText(String text, char[] shareCode, String projectId) {
        validateShareCode(shareCode);
        ConnectionShareEnvelope envelope = validateEnvelope(parseEnvelope(text));
        if (!hasEncryptedPayload(envelope)) {
            return importShareText(text, projectId);
        }
        try {
            byte[] salt = decode(envelope.salt());
            byte[] iv = decode(envelope.iv());
            byte[] encrypted = decode(envelope.encrypted());
            SecretKeySpec key = deriveKey(shareCode, salt, envelope.iterations());
            ConnectionSharePayload secret = gson.fromJson(JsonParser.parseString(
                    new String(decrypt(encrypted, key, iv), StandardCharsets.UTF_8)
            ), ConnectionSharePayload.class);

            return toFormData(envelope, secret, projectId);
        } catch (GeneralSecurityException | IllegalArgumentException | JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid share text or share code", e);
        }
    }

    public boolean isShareText(String text) {
        return text != null && text.contains(PREFIX);
    }

    public boolean requiresShareCode(String text) {
        validateEnvelope(parseEnvelope(text));
        String embeddedShareCode = extractShareCode(text);
        return embeddedShareCode == null || embeddedShareCode.isBlank();
    }

    public String extractShareCode(String text) {
        if (text == null) {
            return null;
        }
        int index = text.indexOf(SHARE_CODE_SEPARATOR);
        if (index < 0) {
            return null;
        }
        String code = text.substring(index + SHARE_CODE_SEPARATOR.length()).trim();
        int newline = code.indexOf('\n');
        return newline >= 0 ? code.substring(0, newline).trim() : code;
    }

    private ConnectionShareEnvelope parseEnvelope(String text) {
        if (!isShareText(text)) {
            throw new IllegalArgumentException("Share text must start with " + PREFIX);
        }
        String normalized = text.trim();
        int prefixIndex = normalized.indexOf(PREFIX);
        String shareBlock = normalized.substring(prefixIndex);
        int codeIndex = shareBlock.indexOf(SHARE_CODE_SEPARATOR);
        String sharePart = codeIndex >= 0 ? shareBlock.substring(0, codeIndex).trim() : shareBlock;
        String payload = sharePart.substring(PREFIX.length()).trim();
        try {
            String json = new String(decode(payload), StandardCharsets.UTF_8);
            ConnectionShareEnvelope envelope = gson.fromJson(json, ConnectionShareEnvelope.class);
            if (envelope == null) {
                throw new IllegalArgumentException("Invalid share text");
            }
            return envelope;
        } catch (IllegalArgumentException | JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid share text", e);
        }
    }

    private ConnectionShareEnvelope validateEnvelope(ConnectionShareEnvelope envelope) {
        if (envelope.version() != VERSION) {
            throw new IllegalArgumentException("Unsupported share version: " + envelope.version());
        }
        if (hasEncryptedPayload(envelope)
                && (!"PBKDF2WithHmacSHA256".equals(envelope.kdf())
                || !"AES-256-GCM".equals(envelope.cipher())
                || envelope.iterations() <= 0)) {
            throw new IllegalArgumentException("Unsupported share encryption settings");
        }
        return envelope;
    }

    private static boolean hasEncryptedPayload(ConnectionShareEnvelope envelope) {
        return envelope.encrypted() != null && !envelope.encrypted().isBlank();
    }

    private ConnectionFormData toFormData(ConnectionShareEnvelope envelope, ConnectionSharePayload secret,
                                          String projectId) {
        return new ConnectionFormData(
                null,
                nullToEmpty(envelope.name()),
                nullToEmpty(envelope.host()),
                envelope.port() <= 0 ? 22 : envelope.port(),
                nullToEmpty(envelope.user()),
                parseAuthType(envelope.authType()),
                secret == null ? "" : nullToEmpty(secret.password()),
                nullToEmpty(envelope.privateKeyPath()),
                secret == null ? "" : nullToEmpty(secret.passphrase()),
                HostKeyVerificationMode.STRICT,
                nullToEmpty(envelope.description()),
                nullToEmpty(envelope.defaultRemotePath()),
                false,
                projectId,
                ConnectionType.SSH,
                null,
                null,
                null
        );
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private static SecretKeySpec deriveKey(char[] shareCode, byte[] salt) throws GeneralSecurityException {
        return deriveKey(shareCode, salt, ITERATIONS);
    }

    private static SecretKeySpec deriveKey(char[] shareCode, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(shareCode, salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] encrypt(byte[] plaintext, SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    private static byte[] decrypt(byte[] encrypted, SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Encoded value is required");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static void validateShareCode(char[] shareCode) {
        if (shareCode == null || shareCode.length == 0) {
            throw new IllegalArgumentException("Share code is required");
        }
    }

    private static AuthenticationType authType(ConnectionFormData form) {
        return form.authenticationType() == null ? AuthenticationType.PASSWORD : form.authenticationType();
    }

    private static AuthenticationType parseAuthType(String value) {
        if (value == null || value.isBlank()) {
            return AuthenticationType.PASSWORD;
        }
        return switch (value.trim().toUpperCase()) {
            case "PRIVATE_KEY", "KEY", "PUBLICKEY" -> AuthenticationType.PRIVATE_KEY;
            default -> AuthenticationType.PASSWORD;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ConnectionShareEnvelope(
            int version,
            String name,
            String host,
            int port,
            String user,
            String authType,
            String privateKeyPath,
            String description,
            String defaultRemotePath,
            boolean favorite,
            String kdf,
            int iterations,
            String cipher,
            String salt,
            String iv,
            String encrypted
    ) {}

    public record ConnectionSharePayload(
            String password,
            String passphrase
    ) {}
}
