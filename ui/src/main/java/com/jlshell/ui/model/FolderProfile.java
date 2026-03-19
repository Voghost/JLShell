package com.jlshell.ui.model;

/**
 * 文件夹列表项模型。
 */
public record FolderProfile(String id, String name, String parentId, String projectId, int sortOrder) {}
