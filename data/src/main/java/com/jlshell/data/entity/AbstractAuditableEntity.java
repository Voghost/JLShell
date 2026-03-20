package com.jlshell.data.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 通用审计基类。
 */
public abstract class AbstractAuditableEntity {

    private String id;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 新增记录前调用：生成 ID 并设置时间戳。 */
    public void prepareInsert() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新记录前调用：刷新 updatedAt。 */
    public void prepareUpdate() {
        updatedAt = Instant.now();
    }
}
