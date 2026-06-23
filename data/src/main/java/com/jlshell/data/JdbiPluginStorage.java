package com.jlshell.data;

import java.util.HashSet;
import java.util.Set;

import com.jlshell.plugin.api.storage.PluginStorage;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JDBI + SQLite 的插件存储实现。
 * 每个实例绑定一个 pluginId，所有操作自动按 pluginId 隔离。
 */
public class JdbiPluginStorage implements PluginStorage {

    private static final Logger log = LoggerFactory.getLogger(JdbiPluginStorage.class);

    private final Jdbi jdbi;
    private final String pluginId;

    public JdbiPluginStorage(Jdbi jdbi, String pluginId) {
        this.jdbi = jdbi;
        this.pluginId = pluginId;
    }

    @Override
    public String get(String key) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT value FROM plugin_storage WHERE plugin_id = :pid AND key = :key")
                        .bind("pid", pluginId)
                        .bind("key", key)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null)
        );
    }

    @Override
    public void put(String key, String value) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO plugin_storage (plugin_id, key, value, updated_at)
                        VALUES (:pid, :key, :value, :now)
                        ON CONFLICT(plugin_id, key) DO UPDATE SET
                            value = :value,
                            updated_at = :now
                        """)
                        .bind("pid", pluginId)
                        .bind("key", key)
                        .bind("value", value)
                        .bind("now", System.currentTimeMillis())
                        .execute()
        );
    }

    @Override
    public void remove(String key) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM plugin_storage WHERE plugin_id = :pid AND key = :key")
                        .bind("pid", pluginId)
                        .bind("key", key)
                        .execute()
        );
    }

    @Override
    public Set<String> keys() {
        return new HashSet<>(jdbi.withHandle(handle ->
                handle.createQuery("SELECT key FROM plugin_storage WHERE plugin_id = :pid ORDER BY key")
                        .bind("pid", pluginId)
                        .mapTo(String.class)
                        .list()
        ));
    }

    @Override
    public void clear() {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM plugin_storage WHERE plugin_id = :pid")
                        .bind("pid", pluginId)
                        .execute()
        );
        log.debug("Cleared all storage for plugin {}", pluginId);
    }
}
