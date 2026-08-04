package com.jlshell.data.service;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.data.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedAppSettingsServiceTest {

    @Test
    void encryptsSensitiveSettingAndKeepsItOutsidePlainKey() {
        MemorySettings delegate = new MemorySettings();
        EncryptedAppSettingsService secure = new EncryptedAppSettingsService(delegate, new MarkerCipher());

        secure.set("account.authToken", "secret-token");

        assertThat(delegate.get("account.authToken")).isEmpty();
        assertThat(delegate.get("secure.account.authToken").orElseThrow())
                .startsWith("encrypted:")
                .doesNotContain("secret-token");
        assertThat(secure.get("account.authToken")).contains("secret-token");
        secure.remove("account.authToken");
        assertThat(secure.get("account.authToken")).isEmpty();
    }

    private static final class MarkerCipher implements CredentialCipher {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + java.util.Base64.getEncoder().encodeToString(plaintext.getBytes());
        }

        @Override
        public String decrypt(String encryptedValue) {
            return new String(java.util.Base64.getDecoder().decode(
                    encryptedValue.substring("encrypted:".length())));
        }
    }

    private static final class MemorySettings implements AppSettingsService {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
