package com.jlshell.data.entity;

/**
 * 连接文件夹实体（纯 POJO，由 JDBI 映射）。
 * parent_id 和 project_id 直接作为 String 字段存储。
 */
public class ConnectionFolderEntity extends AbstractAuditableEntity {

    private String name;
    private String parentId;
    private String projectId;
    private int sortOrder;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
