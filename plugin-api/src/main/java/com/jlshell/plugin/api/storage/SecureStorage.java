package com.jlshell.plugin.api.storage;

import java.util.Optional;
import java.util.Set;

/** 由宿主加密并按插件 ID 隔离的敏感字节存储。 */
public interface SecureStorage {

    boolean available();

    Optional<byte[]> get(String key);

    void put(String key, byte[] value);

    void remove(String key);

    Set<String> keys();

    void clear();

    static SecureStorage unavailable() {
        return UnavailableSecureStorage.INSTANCE;
    }
}

final class UnavailableSecureStorage implements SecureStorage {
    static final UnavailableSecureStorage INSTANCE = new UnavailableSecureStorage();

    private UnavailableSecureStorage() {
    }

    @Override public boolean available() { return false; }
    @Override public Optional<byte[]> get(String key) { return Optional.empty(); }
    @Override public void put(String key, byte[] value) {
        throw new UnsupportedOperationException("secure storage is unavailable");
    }
    @Override public void remove(String key) { }
    @Override public Set<String> keys() { return Set.of(); }
    @Override public void clear() { }
}
