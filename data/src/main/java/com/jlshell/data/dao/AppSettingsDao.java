package com.jlshell.data.dao;

import java.util.Optional;

import com.jlshell.data.entity.AppSettingsEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 应用配置 JDBI DAO。
 * 注意：DB 列名 setting_key/setting_value，通过 SQL 别名 key/value 映射到实体属性。
 */
public interface AppSettingsDao {

    @SqlQuery("SELECT id, setting_key AS key, setting_value AS value, created_at, updated_at " +
            "FROM app_settings WHERE setting_key = :key")
    @RegisterBeanMapper(AppSettingsEntity.class)
    Optional<AppSettingsEntity> findByKey(@Bind("key") String key);

    @SqlUpdate("INSERT INTO app_settings (id, setting_key, setting_value, created_at, updated_at) " +
            "VALUES (:id, :key, :value, :createdAt, :updatedAt)")
    void insert(@BindBean AppSettingsEntity entity);

    @SqlUpdate("UPDATE app_settings SET setting_value=:value, updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean AppSettingsEntity entity);

    @SqlUpdate("DELETE FROM app_settings WHERE setting_key = :key")
    void deleteByKey(@Bind("key") String key);
}
