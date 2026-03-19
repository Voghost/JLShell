package com.jlshell.core.service;

import java.util.Optional;

/**
 * 应用全局配置服务。
 * 提供 key-value 持久化能力，所有模块的配置项均通过此接口读写。
 *
 * <p>扩展方式：直接调用 get/set，key 建议使用点分命名，例如：
 * <ul>
 *   <li>{@code terminal.font.family}</li>
 *   <li>{@code terminal.font.size}</li>
 *   <li>{@code ui.theme}</li>
 *   <li>{@code sftp.defaultLocalPath}</li>
 * </ul>
 */
public interface AppSettingsService {

    /**
     * 读取配置值，不存在时返回 empty。
     */
    Optional<String> get(String key);

    /**
     * 读取配置值，不存在时返回 defaultValue。
     */
    default String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * 写入配置值，key 不存在时自动创建。
     */
    void set(String key, String value);

    /**
     * 删除配置项。
     */
    void remove(String key);
}
