package com.jlshell.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 连接文件夹实体，支持自引用层级结构。
 */
@Entity
@Table(name = "connection_folders")
public class ConnectionFolderEntity extends AbstractAuditableEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = true)
    private ConnectionFolderEntity parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = true)
    private ProjectEntity project;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ConnectionFolderEntity getParent() {
        return parent;
    }

    public void setParent(ConnectionFolderEntity parent) {
        this.parent = parent;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
