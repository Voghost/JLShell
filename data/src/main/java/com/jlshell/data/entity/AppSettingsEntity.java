package com.jlshell.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 应用全局配置存储实体。
 * 采用 key-value 结构，支持未来任意配置项扩展，无需修改 schema。
 */
@Entity
@Table(name = "app_settings")
public class AppSettingsEntity extends AbstractAuditableEntity {

    @Column(name = "setting_key", nullable = false, unique = true, length = 128)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 4096)
    private String value;

    protected AppSettingsEntity() {}

    public AppSettingsEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
