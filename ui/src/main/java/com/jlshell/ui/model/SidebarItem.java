package com.jlshell.ui.model;

import com.jlshell.core.model.ConnectionType;

/**
 * 侧边栏树节点类型。
 */
public sealed interface SidebarItem permits SidebarItem.FolderItem, SidebarItem.ConnectionItem {

    String id();
    String displayName();

    record FolderItem(String id, String displayName, String parentId) implements SidebarItem {}

    record ConnectionItem(String id, String displayName, ConnectionType connectionType, String summary)
            implements SidebarItem {}
}
