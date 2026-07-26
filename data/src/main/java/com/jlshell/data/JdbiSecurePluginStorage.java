package com.jlshell.data;

import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import org.jdbi.v3.core.Jdbi;

/**
 * 使用宿主主密钥加密的插件敏感存储。普通插件存储与安全存储使用不同数据库命名空间，
 * 插件只能看到自己的键；二进制值先编码再交给 AES-GCM 凭证加密器。
 */
public final class JdbiSecurePluginStorage implements SecureStorage {

    private static final String PAYLOAD_PREFIX = "jlshell-secure-v1:";
    private final PluginStorage storage;
    private final CredentialCipher cipher;

    public JdbiSecurePluginStorage(Jdbi jdbi, String pluginId, CredentialCipher cipher) {
        this(new SecureTableStorage(
                Objects.requireNonNull(jdbi, "jdbi"),
                Objects.requireNonNull(pluginId, "pluginId")), cipher);
    }

    JdbiSecurePluginStorage(PluginStorage storage, CredentialCipher cipher) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<byte[]> get(String key) {
        String encrypted = storage.get(requireKey(key));
        if (encrypted == null) {
            return Optional.empty();
        }
        String payload = cipher.decrypt(encrypted);
        if (payload == null || !payload.startsWith(PAYLOAD_PREFIX)) {
            throw new IllegalStateException("Invalid secure plugin storage payload");
        }
        return Optional.of(Base64.getDecoder().decode(payload.substring(PAYLOAD_PREFIX.length())));
    }

    @Override
    public void put(String key, byte[] value) {
        Objects.requireNonNull(value, "value");
        String payload = PAYLOAD_PREFIX + Base64.getEncoder().encodeToString(value.clone());
        storage.put(requireKey(key), cipher.encrypt(payload));
    }

    @Override
    public void remove(String key) {
        storage.remove(requireKey(key));
    }

    @Override
    public Set<String> keys() {
        return Set.copyOf(storage.keys());
    }

    @Override
    public void clear() {
        storage.clear();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("secure storage key required");
        }
        return key;
    }

    private static final class SecureTableStorage implements PluginStorage {
        private final Jdbi jdbi;
        private final String pluginId;

        private SecureTableStorage(Jdbi jdbi, String pluginId) {
            this.jdbi = jdbi;
            this.pluginId = pluginId;
        }

        @Override
        public String get(String key) {
            return jdbi.withHandle(handle -> handle.createQuery(
                            "SELECT value FROM plugin_secure_storage WHERE plugin_id = :pid AND key = :key")
                    .bind("pid", pluginId).bind("key", key).mapTo(String.class).findOne().orElse(null));
        }

        @Override
        public void put(String key, String value) {
            jdbi.useHandle(handle -> handle.createUpdate("""
                            INSERT INTO plugin_secure_storage (plugin_id, key, value, updated_at)
                            VALUES (:pid, :key, :value, :now)
                            ON CONFLICT(plugin_id, key) DO UPDATE SET value = :value, updated_at = :now
                            """)
                    .bind("pid", pluginId).bind("key", key).bind("value", value)
                    .bind("now", System.currentTimeMillis()).execute());
        }

        @Override
        public void remove(String key) {
            jdbi.useHandle(handle -> handle.createUpdate(
                            "DELETE FROM plugin_secure_storage WHERE plugin_id = :pid AND key = :key")
                    .bind("pid", pluginId).bind("key", key).execute());
        }

        @Override
        public Set<String> keys() {
            return new HashSet<>(jdbi.withHandle(handle -> handle.createQuery(
                            "SELECT key FROM plugin_secure_storage WHERE plugin_id = :pid ORDER BY key")
                    .bind("pid", pluginId).mapTo(String.class).list()));
        }

        @Override
        public void clear() {
            jdbi.useHandle(handle -> handle.createUpdate(
                            "DELETE FROM plugin_secure_storage WHERE plugin_id = :pid")
                    .bind("pid", pluginId).execute());
        }
    }
}
