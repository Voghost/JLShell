package com.jlshell.data.entity;

import java.time.Instant;

public class CustomColorSchemeEntity {

    private String id;
    private String name;
    private String colorsJson;
    private long createdAt;
    private long updatedAt;

    public CustomColorSchemeEntity() {}

    public CustomColorSchemeEntity(String id, String name, String colorsJson) {
        this.id = id;
        this.name = name;
        this.colorsJson = colorsJson;
        long now = Instant.now().toEpochMilli();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColorsJson() { return colorsJson; }
    public void setColorsJson(String colorsJson) { this.colorsJson = colorsJson; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
