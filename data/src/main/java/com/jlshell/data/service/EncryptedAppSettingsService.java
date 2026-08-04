package com.jlshell.data.service;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.SecureSettingsService;
import com.jlshell.data.crypto.CredentialCipher;

import java.util.Objects;
import java.util.Optional;

/** 使用宿主 AES-GCM 主密钥加密后写入应用设置表。 */
public final class EncryptedAppSettingsService implements SecureSettingsService {

    private static final String KEY_PREFIX = "secure.";
    private static final String PAYLOAD_PREFIX = "jlshell-secure-setting-v1:";

    private final AppSettingsService settings;
    private final CredentialCipher cipher;

    public EncryptedAppSettingsService(AppSettingsService settings, CredentialCipher cipher) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public Optional<String> get(String key) {
        return settings.get(storageKey(key)).map(encrypted -> {
            String payload = cipher.decrypt(encrypted);
            if (payload == null || !payload.startsWith(PAYLOAD_PREFIX)) {
                throw new IllegalStateException("Invalid secure setting payload");
            }
            return payload.substring(PAYLOAD_PREFIX.length());
        });
    }

    @Override
    public void set(String key, String value) {
        settings.set(storageKey(key), cipher.encrypt(PAYLOAD_PREFIX + Objects.requireNonNull(value, "value")));
    }

    @Override
    public void remove(String key) {
        settings.remove(storageKey(key));
    }

    private static String storageKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secure setting key required");
        }
        return KEY_PREFIX + key;
    }
}
