package com.jlshell.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.plugin.api.storage.PluginStorage;
import org.junit.jupiter.api.Test;
import org.jdbi.v3.core.Jdbi;

class JdbiSecurePluginStorageTest {

    @Test
    void encryptsBinaryValuesAndReturnsDefensiveCopies() {
        MemoryStorage delegate = new MemoryStorage();
        JdbiSecurePluginStorage storage = new JdbiSecurePluginStorage(delegate, new MarkerCipher());
        byte[] source = {0, 1, 2, -1};

        storage.put("token", source);
        source[0] = 99;

        assertThat(delegate.get("token")).startsWith("encrypted:");
        assertThat(delegate.get("token")).doesNotContain(Base64.getEncoder().encodeToString(source));
        byte[] firstRead = storage.get("token").orElseThrow();
        firstRead[1] = 88;
        assertThat(storage.get("token").orElseThrow()).containsExactly(0, 1, 2, -1);
        assertThat(storage.available()).isTrue();
    }

    @Test
    void validatesKeysAndDelegatesLifecycleOperations() {
        MemoryStorage delegate = new MemoryStorage();
        JdbiSecurePluginStorage storage = new JdbiSecurePluginStorage(delegate, new MarkerCipher());
        storage.put("one", new byte[] {1});
        storage.put("two", new byte[] {2});

        assertThat(storage.keys()).containsExactlyInAnyOrder("one", "two");
        storage.remove("one");
        assertThat(storage.get("one")).isEmpty();
        storage.clear();
        assertThat(storage.keys()).isEmpty();
        assertThatThrownBy(() -> storage.put(" ", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sqlStorageKeepsPluginNamespacesIsolated(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + tempDir.resolve("plugins.db"));
        jdbi.useHandle(handle -> handle.execute("""
                CREATE TABLE plugin_secure_storage (
                    plugin_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL,
                    PRIMARY KEY (plugin_id, key))
                """));
        JdbiSecurePluginStorage first = new JdbiSecurePluginStorage(jdbi, "plugin-a", new MarkerCipher());
        JdbiSecurePluginStorage second = new JdbiSecurePluginStorage(jdbi, "plugin-b", new MarkerCipher());

        first.put("token", new byte[] {1});
        second.put("token", new byte[] {2});

        assertThat(first.get("token").orElseThrow()).containsExactly(1);
        assertThat(second.get("token").orElseThrow()).containsExactly(2);
        String storedValue = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT value FROM plugin_secure_storage WHERE plugin_id = 'plugin-a'")
                .mapTo(String.class).one());
        assertThat(storedValue).startsWith("encrypted:");
    }

    private static final class MarkerCipher implements CredentialCipher {
        @Override public String encrypt(String plaintext) { return "encrypted:" + plaintext; }
        @Override public String decrypt(String encryptedValue) {
            return encryptedValue.substring("encrypted:".length());
        }
    }

    private static final class MemoryStorage implements PluginStorage {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Set<String> keys() { return new HashSet<>(values.keySet()); }
        @Override public void clear() { values.clear(); }
    }
}
