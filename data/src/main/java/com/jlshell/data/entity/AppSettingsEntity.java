package com.jlshell.data.entity;

/**
 * 应用全局配置存储实体（纯 POJO，由 JDBI 映射）。
 */
public class AppSettingsEntity extends AbstractAuditableEntity {

    private String key;
    private String value;

    public AppSettingsEntity() {}

    public AppSettingsEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
