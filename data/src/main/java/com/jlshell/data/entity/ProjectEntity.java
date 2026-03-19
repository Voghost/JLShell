package com.jlshell.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 项目实体，用于对连接进行分组管理。
 */
@Entity
@Table(name = "projects")
public class ProjectEntity extends AbstractAuditableEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
