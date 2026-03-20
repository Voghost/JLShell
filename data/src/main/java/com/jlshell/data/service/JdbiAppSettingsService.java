package com.jlshell.data.service;

import java.util.Optional;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.data.dao.AppSettingsDao;
import com.jlshell.data.entity.AppSettingsEntity;
import org.jdbi.v3.core.Jdbi;

/**
 * 基于 SQLite + JDBI 的配置持久化实现。
 */
public class JdbiAppSettingsService implements AppSettingsService {

    private final Jdbi jdbi;

    public JdbiAppSettingsService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<String> get(String key) {
        return jdbi.withHandle(handle ->
                handle.attach(AppSettingsDao.class).findByKey(key).map(AppSettingsEntity::getValue)
        );
    }

    @Override
    public void set(String key, String value) {
        jdbi.useHandle(handle -> {
            AppSettingsDao dao = handle.attach(AppSettingsDao.class);
            Optional<AppSettingsEntity> existing = dao.findByKey(key);
            if (existing.isPresent()) {
                AppSettingsEntity entity = existing.get();
                entity.setValue(value);
                entity.prepareUpdate();
                dao.update(entity);
            } else {
                AppSettingsEntity entity = new AppSettingsEntity(key, value);
                entity.prepareInsert();
                dao.insert(entity);
            }
        });
    }

    @Override
    public void remove(String key) {
        jdbi.useHandle(handle -> handle.attach(AppSettingsDao.class).deleteByKey(key));
    }
}
