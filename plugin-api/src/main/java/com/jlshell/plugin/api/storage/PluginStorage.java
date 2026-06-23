package com.jlshell.plugin.api.storage;

import java.util.Set;

/**
 * 插件持久存储代理。每个插件实例获得独立命名空间，
 * 只能读写自己的数据，不能访问其他插件的数据。
 * <p>
 * 旧版宿主无存储时 {@code context.storage()} 返回 {@code null}，
 * 插件应做 null 检查后使用。
 */
public interface PluginStorage {

    /**
     * 读取 key 对应的值。
     *
     * @param key 存储键
     * @return 值，不存在时返回 {@code null}
     */
    String get(String key);

    /**
     * 读取 key 对应的值，不存在时返回默认值。
     */
    default String get(String key, String defaultValue) {
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 写入 key-value，覆盖已有值。
     */
    void put(String key, String value);

    /**
     * 删除 key，不存在时静默忽略。
     */
    void remove(String key);

    /**
     * 列出该插件所有 key。
     */
    Set<String> keys();

    /**
     * 清除该插件所有数据。
     */
    void clear();
}
