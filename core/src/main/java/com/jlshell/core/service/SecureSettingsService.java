package com.jlshell.core.service;

import java.util.Optional;

/**
 * 应用级敏感配置存储。实现必须在持久化前加密值，不得把明文写入普通设置表。
 */
public interface SecureSettingsService {

    Optional<String> get(String key);

    void set(String key, String value);

    void remove(String key);
}
