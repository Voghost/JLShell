package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private Consumer<SidebarItem> onDelete;
    private BiConsumer<String, Integer> onNewSubFolder;
    private BiConsumer<String, String> onRenameFolder;
    private BiConsumer<List<SidebarItem>, String> onMove;

    private final Map<String, Integer> folderDepths = new HashMap<>();

    public SidebarTreeView(I18nService i18n, int maxFolderDepth) {
        this.i18n = i18n;
        this.maxFolderDepth = maxFolderDepth;
        root = new TreeItem<>(null);
        root.setExpanded(true);
        treeView = new TreeView<>(root);
        treeView.setShowRoot(false);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setCellFactory(tv -> new SidebarTreeCell());
    }

    // ── Icon helpers ──────────────────────────────────────────────────

    // ── Icon helpers ──────────────────────────────────────────────────

    /** 加载 SVG 图标为 ImageView */
    private static ImageView loadSvgIcon(String resourcePath, double size) {
        var url = SidebarTreeView.class.getResource(resourcePath);
        if (url == null) return null;
        Image img = new Image(url.toExternalForm(), size, size, true, true);
        return new ImageView(img);
    }

    public TreeView<SidebarItem> getTreeView() { return treeView; }

    public void setOnConnect(Runnable v) { this.onConnect = v; }
    public void setOnEdit(Consumer<SidebarItem> v) { this.onEdit = v; }
    public void setOnDelete(Consumer<SidebarItem> v) { this.onDelete = v; }
    public void setOnNewSubFolder(BiConsumer<String, Integer> v) { this.onNewSubFolder = v; }
    public void setOnRenameFolder(BiConsumer<String, String> v) { this.onRenameFolder = v; }
    public void setOnMove(BiConsumer<List<SidebarItem>, String> v) { this.onMove = v; }

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
                    ImageView icon = loadSvgIcon("/icons/folder.svg", 16);
                    Label name = new Label(folder.displayName());
                    name.getStyleClass().add("folder-item-name");
                    HBox box = new HBox(5, icon, name);
                    box.getStyleClass().add("folder-item");
                    setGraphic(box); setText(null);
                    int depth = folderDepths.getOrDefault(folder.id(), 0);
                    setContextMenu(buildFolderContextMenu(folder, depth));
                }
                case SidebarItem.ConnectionItem conn -> {
                    String iconPath = conn.connectionType() == ConnectionType.LOCAL_SHELL
                            ? "/icons/mac.svg" : "/icons/linux.svg";
                    ImageView icon = loadSvgIcon(iconPath, 16);
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
            MenuItem connect = new MenuItem(i18n.get("action.connect"));
            MenuItem edit    = new MenuItem(i18n.get("action.editConnection"));
            MenuItem delete  = new MenuItem(i18n.get("action.deleteConnection"));
            connect.setOnAction(e -> { if (onConnect != null) onConnect.run(); });
            edit.setOnAction(e -> { if (onEdit != null) onEdit.accept(conn); });
            delete.setOnAction(e -> { if (onDelete != null) onDelete.accept(conn); });
            return new ContextMenu(connect, edit, new SeparatorMenuItem(), delete);
        }

        private ContextMenu buildFolderContextMenu(SidebarItem.FolderItem folder, int depth) {
            ContextMenu menu = new ContextMenu();
            if (depth + 1 < maxFolderDepth) {
                MenuItem newSub = new MenuItem(i18n.get("folder.newSub"));
                newSub.setOnAction(e -> { if (onNewSubFolder != null) onNewSubFolder.accept(folder.id(), depth); });
                menu.getItems().add(newSub);
            }
            MenuItem rename = new MenuItem(i18n.get("folder.rename"));
            rename.setOnAction(e -> { if (onRenameFolder != null) onRenameFolder.accept(folder.id(), folder.displayName()); });
            MenuItem delete = new MenuItem(i18n.get("folder.delete"));
            delete.setOnAction(e -> { if (onDelete != null) onDelete.accept(folder); });
            menu.getItems().addAll(rename, new SeparatorMenuItem(), delete);
            return menu;
        }
    }
}
