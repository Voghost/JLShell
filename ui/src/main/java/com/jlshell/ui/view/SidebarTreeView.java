package com.jlshell.ui.view;

import com.jlshell.ui.model.*;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.swing.SwingMenuBridge;
import com.jlshell.ui.swing.SwingMenuBridge.Item;
import java.util.*;
import java.util.function.*;

/** 连接树动作与选择状态，绘制、搜索与拖放由原生工作台负责。 */
public final class SidebarTreeView {
    private final I18nService i18n;
    private final int maxFolderDepth;
    private List<SidebarItem> selected = List.of();
    private List<FolderProfile> folders = List.of();
    private Set<String> favoriteIds = Set.of();
    private Runnable onConnect, onNewConnectionInEmpty, onNewFolderInEmpty;
    private Consumer<SidebarItem> onEdit, onDuplicate, onCopyShare;
    private Consumer<List<SidebarItem>> onDelete;
    private BiConsumer<String, Boolean> onFavorite;
    private Consumer<String> onImportShare, onNewConnectionInFolder;
    private BiConsumer<String, Integer> onNewSubFolder;
    private BiConsumer<String, String> onRenameFolder;
    private BiConsumer<List<SidebarItem>, String> onMove;
    private Consumer<Set<String>> onCollapsedFoldersChanged;

    public SidebarTreeView(I18nService i18n, int maxFolderDepth) { this.i18n = i18n; this.maxFolderDepth = maxFolderDepth; }
    public void selectItems(List<SidebarItem> items) { selected = List.copyOf(items); }
    public SidebarItem getSelectedItem() { return selected.isEmpty() ? null : selected.getFirst(); }
    public ConnectionProfile getSelectedConnection(List<ConnectionProfile> profiles) {
        return selected.stream().filter(SidebarItem.ConnectionItem.class::isInstance)
                .flatMap(item -> profiles.stream().filter(profile -> profile.id().equals(item.id()))).findFirst().orElse(null);
    }
    public void populate(List<FolderProfile> folders, List<ConnectionProfile> profiles, Set<String> collapsed) {
        this.folders = List.copyOf(folders);
        favoriteIds = profiles.stream().filter(ConnectionProfile::favorite).map(ConnectionProfile::id).collect(java.util.stream.Collectors.toSet());
        Set<String> ids = new HashSet<>(); profiles.forEach(profile -> ids.add(profile.id())); folders.forEach(folder -> ids.add(folder.id()));
        selected = selected.stream().filter(item -> ids.contains(item.id())).toList();
    }
    public void moveItems(List<SidebarItem> items, String folderId) { if (onMove != null) onMove.accept(items, folderId); }
    private Item action(String key, Runnable action) { return SwingMenuBridge.action(i18n.get(key), action); }
    public List<Item> contextMenuFor(SidebarItem item) {
        if (item instanceof SidebarItem.ConnectionItem connection) {
            boolean favorite = favoriteIds.contains(connection.id());
            return List.of(action("action.connect", onConnect), action("action.editConnection", () -> onEdit.accept(connection)),
                    action("action.duplicateConnection", () -> onDuplicate.accept(connection)), action("connection.share.copy", () -> onCopyShare.accept(connection)),
                    action(favorite ? "connection.favorite.remove" : "connection.favorite.add", () -> onFavorite.accept(connection.id(), !favorite)),
                    SwingMenuBridge.separator(), action("action.deleteConnection", () -> onDelete.accept(selected)));
        }
        if (item instanceof SidebarItem.FolderItem folder) {
            List<Item> result = new ArrayList<>();
            result.add(action("action.newConnection", () -> onNewConnectionInFolder.accept(folder.id())));
            result.add(action("connection.share.import", () -> onImportShare.accept(folder.id())));
            Map<String, FolderProfile> byId = new HashMap<>(); folders.forEach(value -> byId.put(value.id(), value));
            int depth = 0; Set<String> visited = new HashSet<>();
            for (FolderProfile current = byId.get(folder.id()); current != null && current.parentId() != null && visited.add(current.id()); current = byId.get(current.parentId())) depth++;
            int currentDepth = depth;
            if (depth + 1 < maxFolderDepth) result.add(action("folder.newSub", () -> onNewSubFolder.accept(folder.id(), currentDepth)));
            result.add(action("folder.rename", () -> onRenameFolder.accept(folder.id(), folder.displayName())));
            result.add(SwingMenuBridge.separator()); result.add(action("folder.delete", () -> onDelete.accept(selected)));
            return List.copyOf(result);
        }
        return List.of(action("action.newConnection", onNewConnectionInEmpty), action("folder.new", onNewFolderInEmpty));
    }
    public void setOnConnect(Runnable v) { this.onConnect = v; }
    public void setOnEdit(Consumer<SidebarItem> v) { this.onEdit = v; }
    public void setOnDelete(Consumer<List<SidebarItem>> v) { this.onDelete = v; }
    public void setOnDuplicate(Consumer<SidebarItem> v) { this.onDuplicate = v; }
    public void setOnCopyShare(Consumer<SidebarItem> v) { this.onCopyShare = v; }
    public void setOnFavorite(BiConsumer<String, Boolean> v) { this.onFavorite = v; }
    public void setOnImportShare(Consumer<String> v) { this.onImportShare = v; }
    public void setOnNewConnectionInEmpty(Runnable v) { this.onNewConnectionInEmpty = v; }
    public void setOnNewFolderInEmpty(Runnable v) { this.onNewFolderInEmpty = v; }
    public void setOnNewSubFolder(BiConsumer<String, Integer> v) { this.onNewSubFolder = v; }
    public void setOnRenameFolder(BiConsumer<String, String> v) { this.onRenameFolder = v; }
    public void setOnMove(BiConsumer<List<SidebarItem>, String> v) { this.onMove = v; }
    public void setOnNewConnectionInFolder(Consumer<String> v) { this.onNewConnectionInFolder = v; }
    public void setOnCollapsedFoldersChanged(Consumer<Set<String>> v) { this.onCollapsedFoldersChanged = v; }
}
