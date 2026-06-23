package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.service.I18nService;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.PopupWindow;

/**
 * 侧边栏 TreeView：支持多选、拖拽到文件夹、分层文件夹（最多 maxFolderDepth 层）。
 */
public class SidebarTreeView {

    /** 拖拽时放在 Dragboard 里的自定义格式 */
    private static final DataFormat DRAG_FORMAT = new DataFormat("application/jlshell-sidebar");

    private final TreeView<SidebarItem> treeView;
    private final TreeItem<SidebarItem> root;
    private final int maxFolderDepth;
    private final I18nService i18n;

    private Runnable onConnect;
    private Consumer<SidebarItem> onEdit;
    private Consumer<List<SidebarItem>> onDelete;
    private Consumer<SidebarItem> onDuplicate;
    private Runnable onNewConnectionInEmpty;
    private Runnable onNewFolderInEmpty;
    private BiConsumer<String, Integer> onNewSubFolder;
    private BiConsumer<String, String> onRenameFolder;
    private BiConsumer<List<SidebarItem>, String> onMove;
    private Consumer<String> onNewConnectionInFolder;

    private final Map<String, Integer> folderDepths = new HashMap<>();

    /** 完整树结构快照，用于搜索过滤时恢复。key = TreeItem, value = 该节点的原始 children。 */
    private final Map<TreeItem<SidebarItem>, List<TreeItem<SidebarItem>>> originalChildren = new HashMap<>();
    /** 当前搜索关键词（小写），null 或空表示不过滤。 */
    private String currentFilter;

    public SidebarTreeView(I18nService i18n, int maxFolderDepth) {
        this.i18n = i18n;
        this.maxFolderDepth = maxFolderDepth;
        root = new TreeItem<>(null);
        root.setExpanded(true);
        treeView = new TreeView<>(root);
        treeView.setShowRoot(false);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setCellFactory(tv -> new SidebarTreeCell());
        treeView.setContextMenu(createEmptyAreaContextMenu());
    }

    private ContextMenu createEmptyAreaContextMenu() {
        MenuItem newConn = new MenuItem(i18n.get("action.newConnection"));
        newConn.setOnAction(e -> { if (onNewConnectionInEmpty != null) onNewConnectionInEmpty.run(); });
        MenuItem newFolder = new MenuItem(i18n.get("sidebar.newFolder"));
        newFolder.setOnAction(e -> { if (onNewFolderInEmpty != null) onNewFolderInEmpty.run(); });
        return transparentPopup(new ContextMenu(newConn, newFolder));
    }

    /** Make the popup window transparent so rounded corners don't show corner fill. */
    private static ContextMenu transparentPopup(ContextMenu menu) {
        menu.setOnShown(e -> {
            javafx.application.Platform.runLater(() -> {
                // The ContextMenu itself is a PopupWindow; make its scene transparent
                var scene = menu.getScene();
                if (scene != null) {
                    scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                    // Also make the root pane transparent in case it has its own fill
                    if (scene.getRoot() != null) {
                        scene.getRoot().setStyle("-fx-background-color: transparent;");
                    }
                }
            });
        });
        return menu;
    }

    /** 从 SVG 文件提取 path 数据，返回 Region（通过 SVGPath shape 显示） */
    private static Region loadSvgShape(String resourcePath, double size) {
        try (var is = SidebarTreeView.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            String content = new String(is.readAllBytes());
            String pathData = extractSvgPath(content);
            if (pathData == null) return null;
            Region region = new Region();
            region.setMinSize(size, size);
            region.setMaxSize(size, size);
            region.setPrefSize(size, size);
            javafx.scene.shape.SVGPath svg = new javafx.scene.shape.SVGPath();
            svg.setContent(pathData);
            region.setShape(svg);
            region.setStyle("-fx-scale-shape:true;");
            return region;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSvgPath(String svgContent) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < svgContent.length()) {
            int start = svgContent.indexOf("d=\"", idx);
            if (start == -1) break;
            if (start > 0 && Character.isLetterOrDigit(svgContent.charAt(start - 1))) {
                idx = start + 3;
                continue;
            }
            start += 3;
            int end = svgContent.indexOf("\"", start);
            if (end == -1) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(svgContent.substring(start, end));
            idx = end + 1;
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public TreeView<SidebarItem> getTreeView() { return treeView; }

    public void setOnConnect(Runnable v) { this.onConnect = v; }
    public void setOnEdit(Consumer<SidebarItem> v) { this.onEdit = v; }
    public void setOnDelete(Consumer<List<SidebarItem>> v) { this.onDelete = v; }
    public void setOnDuplicate(Consumer<SidebarItem> v) { this.onDuplicate = v; }
    public void setOnNewConnectionInEmpty(Runnable v) { this.onNewConnectionInEmpty = v; }
    public void setOnNewFolderInEmpty(Runnable v) { this.onNewFolderInEmpty = v; }
    public void setOnNewSubFolder(BiConsumer<String, Integer> v) { this.onNewSubFolder = v; }
    public void setOnRenameFolder(BiConsumer<String, String> v) { this.onRenameFolder = v; }
    public void setOnMove(BiConsumer<List<SidebarItem>, String> v) { this.onMove = v; }
    public void setOnNewConnectionInFolder(Consumer<String> v) { this.onNewConnectionInFolder = v; }

    public void populate(List<FolderProfile> folders, List<ConnectionProfile> connections) {
        root.getChildren().clear();
        folderDepths.clear();

        Map<String, TreeItem<SidebarItem>> folderItems = new HashMap<>();

        for (FolderProfile folder : folders) {
            TreeItem<SidebarItem> item = new TreeItem<>(
                    new SidebarItem.FolderItem(folder.id(), folder.name(), folder.parentId()));
            item.setExpanded(true);
            folderItems.put(folder.id(), item);
        }

        for (FolderProfile folder : folders) {
            TreeItem<SidebarItem> item = folderItems.get(folder.id());
            if (folder.parentId() != null && folderItems.containsKey(folder.parentId())) {
                folderItems.get(folder.parentId()).getChildren().add(item);
                folderDepths.put(folder.id(), folderDepths.getOrDefault(folder.parentId(), 0) + 1);
            } else {
                root.getChildren().add(item);
                folderDepths.put(folder.id(), 0);
            }
        }

        for (ConnectionProfile conn : connections) {
            TreeItem<SidebarItem> item = new TreeItem<>(
                    new SidebarItem.ConnectionItem(conn.id(), conn.displayName(), conn.connectionType(), conn.summary()));
            if (conn.folderId() != null && folderItems.containsKey(conn.folderId())) {
                folderItems.get(conn.folderId()).getChildren().add(item);
            } else {
                root.getChildren().add(item);
            }
        }

        // 保存完整树结构快照，供搜索过滤使用
        saveOriginalStructure();
    }

    // ── 搜索过滤 ──────────────────────────────────────────────────────

    /** 在 populate() 末尾调用，保存完整树结构快照供过滤使用。 */
    private void saveOriginalStructure() {
        originalChildren.clear();
        saveChildrenRecursive(root);
    }

    private void saveChildrenRecursive(TreeItem<SidebarItem> item) {
        List<TreeItem<SidebarItem>> childrenCopy = new ArrayList<>(item.getChildren());
        originalChildren.put(item, childrenCopy);
        for (TreeItem<SidebarItem> child : childrenCopy) {
            saveChildrenRecursive(child);
        }
    }

    /**
     * 应用搜索过滤。filterText 为空或 null 时恢复完整树。
     * 按文件夹名、连接名、连接 summary（user@host:port）做大小写无关匹配。
     * 匹配文件夹的子项也会全部显示；匹配连接的祖先文件夹也会被保留并展开。
     */
    public void applyFilter(String filterText) {
        currentFilter = (filterText == null || filterText.isBlank()) ? null : filterText.toLowerCase();
        if (currentFilter == null) {
            restoreFullTree();
            return;
        }

        Set<TreeItem<SidebarItem>> matchingItems = new HashSet<>();
        Set<TreeItem<SidebarItem>> requiredParents = new HashSet<>();

        // 构建 child → parent 的映射（从 originalChildren 推导，不依赖实时 getParent）
        Map<TreeItem<SidebarItem>, TreeItem<SidebarItem>> childToOriginalParent = new HashMap<>();
        for (Map.Entry<TreeItem<SidebarItem>, List<TreeItem<SidebarItem>>> entry : originalChildren.entrySet()) {
            for (TreeItem<SidebarItem> child : entry.getValue()) {
                childToOriginalParent.put(child, entry.getKey());
            }
        }

        // 遍历所有原始 children，找出匹配节点
        for (List<TreeItem<SidebarItem>> children : originalChildren.values()) {
            for (TreeItem<SidebarItem> child : children) {
                SidebarItem value = child.getValue();
                if (value == null) continue;

                boolean matches = switch (value) {
                    case SidebarItem.FolderItem folder ->
                            folder.displayName().toLowerCase().contains(currentFilter);
                    case SidebarItem.ConnectionItem conn ->
                            conn.displayName().toLowerCase().contains(currentFilter)
                            || conn.summary().toLowerCase().contains(currentFilter);
                };

                if (matches) {
                    matchingItems.add(child);
                    // 匹配文件夹时，其所有子节点也应该显示
                    if (value instanceof SidebarItem.FolderItem) {
                        addAllDescendants(child, matchingItems);
                    }
                    // 将所有祖先加入 requiredParents
                    TreeItem<SidebarItem> ancestor = childToOriginalParent.get(child);
                    while (ancestor != null && ancestor.getValue() != null) {
                        requiredParents.add(ancestor);
                        ancestor = childToOriginalParent.get(ancestor);
                    }
                }
            }
        }

        // 根据匹配结果重建每个节点的可见 children
        for (Map.Entry<TreeItem<SidebarItem>, List<TreeItem<SidebarItem>>> entry : originalChildren.entrySet()) {
            TreeItem<SidebarItem> parent = entry.getKey();
            List<TreeItem<SidebarItem>> origChildren = entry.getValue();

            List<TreeItem<SidebarItem>> visibleChildren = new ArrayList<>();
            for (TreeItem<SidebarItem> origChild : origChildren) {
                if (matchingItems.contains(origChild) || requiredParents.contains(origChild)) {
                    visibleChildren.add(origChild);
                }
            }
            parent.getChildren().setAll(visibleChildren);
            if (requiredParents.contains(parent)) {
                parent.setExpanded(true);
            }
        }
    }

    /** 递归添加所有后代节点到 matchingItems。 */
    private void addAllDescendants(TreeItem<SidebarItem> item, Set<TreeItem<SidebarItem>> matchingItems) {
        List<TreeItem<SidebarItem>> children = originalChildren.get(item);
        if (children != null) {
            for (TreeItem<SidebarItem> child : children) {
                matchingItems.add(child);
                addAllDescendants(child, matchingItems);
            }
        }
    }

    /** 恢复完整树（清除过滤）。 */
    private void restoreFullTree() {
        for (Map.Entry<TreeItem<SidebarItem>, List<TreeItem<SidebarItem>>> entry : originalChildren.entrySet()) {
            entry.getKey().getChildren().setAll(entry.getValue());
        }
    }

    public SidebarItem getSelectedItem() {
        TreeItem<SidebarItem> sel = treeView.getSelectionModel().getSelectedItem();
        return sel != null ? sel.getValue() : null;
    }

    public ConnectionProfile getSelectedConnection(List<ConnectionProfile> profiles) {
        SidebarItem item = getSelectedItem();
        if (!(item instanceof SidebarItem.ConnectionItem ci)) return null;
        return profiles.stream().filter(p -> p.id().equals(ci.id())).findFirst().orElse(null);
    }

    private List<SidebarItem> getSelectedItems() {
        ObservableList<TreeItem<SidebarItem>> selected = treeView.getSelectionModel().getSelectedItems();
        List<SidebarItem> result = new ArrayList<>();
        for (TreeItem<SidebarItem> ti : selected) {
            if (ti != null && ti.getValue() != null) result.add(ti.getValue());
        }
        return result;
    }

    // ── Cell factory ──────────────────────────────────────────────────

    private class SidebarTreeCell extends TreeCell<SidebarItem> {

        private static final String DROP_HIGHLIGHT =
                "-fx-border-color: #4d9cf8; -fx-border-width: 1; -fx-border-radius: 3;";

        SidebarTreeCell() {
            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && getItem() instanceof SidebarItem.ConnectionItem) {
                    if (onConnect != null) onConnect.run();
                }
            });

            setOnDragDetected(event -> {
                List<SidebarItem> items = getSelectedItems();
                if (items.isEmpty()) return;
                SidebarItem first = items.get(0);
                boolean allSameType = items.stream().allMatch(it ->
                        (it instanceof SidebarItem.ConnectionItem) == (first instanceof SidebarItem.ConnectionItem));
                if (!allSameType) { event.consume(); return; }

                StringBuilder sb = new StringBuilder();
                for (SidebarItem it : items) {
                    if (sb.length() > 0) sb.append(',');
                    if (it instanceof SidebarItem.ConnectionItem ci) sb.append("conn:").append(ci.id());
                    else if (it instanceof SidebarItem.FolderItem fi) sb.append("folder:").append(fi.id());
                }
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.put(DRAG_FORMAT, sb.toString());
                db.setContent(cc);
                event.consume();
            });

            setOnDragOver(event -> {
                if (event.getGestureSource() != this
                        && event.getDragboard().hasContent(DRAG_FORMAT)
                        && getItem() instanceof SidebarItem.FolderItem) {
                    event.acceptTransferModes(TransferMode.MOVE);
                    setStyle(DROP_HIGHLIGHT);
                }
                event.consume();
            });

            setOnDragExited(event -> { setStyle(null); event.consume(); });

            setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasContent(DRAG_FORMAT) && getItem() instanceof SidebarItem.FolderItem targetFolder) {
                    List<SidebarItem> dragged = decodeDragPayload((String) db.getContent(DRAG_FORMAT));
                    if (!dragged.isEmpty() && onMove != null) {
                        onMove.accept(dragged, targetFolder.id());
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });

            treeView.setOnDragOver(event -> {
                if (event.getDragboard().hasContent(DRAG_FORMAT))
                    event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            });

            treeView.setOnDragDropped((DragEvent event) -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasContent(DRAG_FORMAT)) {
                    List<SidebarItem> dragged = decodeDragPayload((String) db.getContent(DRAG_FORMAT));
                    if (!dragged.isEmpty() && onMove != null) {
                        onMove.accept(dragged, null);
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
        }

        private List<SidebarItem> decodeDragPayload(String payload) {
            List<SidebarItem> result = new ArrayList<>();
            for (String token : payload.split(",")) {
                String[] parts = token.split(":", 2);
                if (parts.length != 2) continue;
                if ("conn".equals(parts[0])) result.add(new SidebarItem.ConnectionItem(parts[1], "", null, ""));
                else if ("folder".equals(parts[0])) result.add(new SidebarItem.FolderItem(parts[1], "", null));
            }
            return result;
        }

        @Override
        protected void updateItem(SidebarItem item, boolean empty) {
            super.updateItem(item, empty);
            setStyle(null);
            if (empty || item == null) {
                setText(null); setGraphic(null); setContextMenu(null);
                return;
            }
            switch (item) {
                case SidebarItem.FolderItem folder -> {
                    Region icon = loadSvgShape("/icons/folder.svg", 16);
                    icon.getStyleClass().add("sidebar-icon-folder");
                    Label name = new Label(folder.displayName());
                    name.getStyleClass().add("folder-item-name");
                    HBox box = new HBox(5, icon, name);
                    box.getStyleClass().add("folder-item");
                    setGraphic(box); setText(null);
                    int depth = folderDepths.getOrDefault(folder.id(), 0);
                    setContextMenu(buildFolderContextMenu(folder, depth));
                }
                case SidebarItem.ConnectionItem conn -> {
                    String iconPath;
                    if (conn.connectionType() == ConnectionType.LOCAL_SHELL) {
                        String os = System.getProperty("os.name", "").toLowerCase();
                        if (os.contains("mac") || os.contains("darwin")) {
                            iconPath = "/icons/mac.svg";
                        } else if (os.contains("win")) {
                            iconPath = "/icons/windows.svg";
                        } else {
                            iconPath = "/icons/linux.svg";
                        }
                    } else {
                        iconPath = "/icons/server.svg";
                    }
                    Region icon = loadSvgShape(iconPath, 16);
                    icon.getStyleClass().add("sidebar-icon-server");
                    Label name = new Label(conn.displayName());
                    name.getStyleClass().add("conn-cell-name");
                    Label summary = new Label(conn.summary());
                    summary.getStyleClass().add("conn-cell-summary");
                    VBox textBox = new VBox(1, name, summary);
                    HBox box = new HBox(6, icon, textBox);
                    box.getStyleClass().add("connection-item");
                    setGraphic(box); setText(null);
                    setContextMenu(buildConnectionContextMenu(conn));
                }
            }
        }

        private ContextMenu buildConnectionContextMenu(SidebarItem.ConnectionItem conn) {
            MenuItem connect  = new MenuItem(i18n.get("action.connect"));
            MenuItem edit     = new MenuItem(i18n.get("action.editConnection"));
            MenuItem duplicate = new MenuItem(i18n.get("action.duplicateConnection"));
            MenuItem delete   = new MenuItem(i18n.get("action.deleteConnection"));
            connect.setOnAction(e -> { if (onConnect != null) onConnect.run(); });
            edit.setOnAction(e -> { if (onEdit != null) onEdit.accept(conn); });
            duplicate.setOnAction(e -> { if (onDuplicate != null) onDuplicate.accept(conn); });
            delete.setOnAction(e -> { if (onDelete != null) onDelete.accept(getSelectedItems()); });
            return transparentPopup(new ContextMenu(connect, edit, duplicate, new SeparatorMenuItem(), delete));
        }

        private ContextMenu buildFolderContextMenu(SidebarItem.FolderItem folder, int depth) {
            ContextMenu menu = new ContextMenu();

            MenuItem newConn = new MenuItem(i18n.get("action.newConnection"));
            newConn.setOnAction(e -> { if (onNewConnectionInFolder != null) onNewConnectionInFolder.accept(folder.id()); });
            menu.getItems().add(newConn);

            if (depth + 1 < maxFolderDepth) {
                MenuItem newSub = new MenuItem(i18n.get("folder.newSub"));
                newSub.setOnAction(e -> { if (onNewSubFolder != null) onNewSubFolder.accept(folder.id(), depth); });
                menu.getItems().add(newSub);
            }
            MenuItem rename = new MenuItem(i18n.get("folder.rename"));
            rename.setOnAction(e -> { if (onRenameFolder != null) onRenameFolder.accept(folder.id(), folder.displayName()); });
            MenuItem delete = new MenuItem(i18n.get("folder.delete"));
            delete.setOnAction(e -> { if (onDelete != null) onDelete.accept(getSelectedItems()); });
            menu.getItems().addAll(rename, new SeparatorMenuItem(), delete);
            return transparentPopup(menu);
        }
    }
}
