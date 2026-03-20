package com.jlshell.data.entity;

/**
 * 项目实体（纯 POJO，由 JDBI 映射）。
 */
public class ProjectEntity extends AbstractAuditableEntity {

    private String name;
    private String description;
    private int sortOrder;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
