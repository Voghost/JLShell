package com.jlshell.data.service;

import java.util.Optional;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.data.entity.AppSettingsEntity;
import com.jlshell.data.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 SQLite 的配置持久化实现。
 */
@Service
@Transactional
public class JpaAppSettingsService implements AppSettingsService {

    private final AppSettingsRepository repository;

    public JpaAppSettingsService(AppSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repository.findByKey(key).map(AppSettingsEntity::getValue);
    }

    @Override
    public void set(String key, String value) {
        AppSettingsEntity entity = repository.findByKey(key)
                .orElseGet(() -> new AppSettingsEntity(key, value));
        entity.setValue(value);
        repository.save(entity);
    }

    @Override
    public void remove(String key) {
        repository.findByKey(key).ifPresent(repository::delete);
    }
}
