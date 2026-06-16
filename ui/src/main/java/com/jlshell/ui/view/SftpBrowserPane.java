package com.jlshell.ui.view;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.exception.TransferCancelledException;
import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.sftp.model.TransferDirection;
import com.jlshell.sftp.model.TransferProgress;
import com.jlshell.sftp.model.TransferRequest;
import com.jlshell.sftp.model.TransferResumeMode;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.service.TransferProgressListener;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.LocalFileEntry;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.viewmodel.SftpBrowserViewModel;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * SFTP 文件面板。本地/远程各自上层文件夹树 + 下层文件详情表。
 */
public class SftpBrowserPane extends BorderPane {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final String RES_FOLDER   = "/icons/folder-ftp.svg";
    private static final String RES_FILE     = "/icons/file-ftp.svg";
    private static final String RES_UP       = "/icons/up.svg";
    private static final String RES_REFRESH  = "/icons/refresh.svg";
    private static final String RES_UPLOAD   = "/icons/upload.svg";
    private static final String RES_DOWNLOAD = "/icons/download.svg";
    private static final String RES_RENAME   = "/icons/rename.svg";
    private static final String RES_DELETE   = "/icons/delete.svg";
    private static final String RES_MKDIR    = "/icons/new.svg";
    private static final String RES_HOME     = "/icons/home.svg";

    private final SshSession sshSession;
    private final SftpService sftpService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final SftpBrowserViewModel viewModel = new SftpBrowserViewModel();

    // Local pane
    private final TreeView<FileNode>        localDirTree  = new TreeView<>();
    private final TableView<LocalFileEntry> localFileTable = new TableView<>();

    // Remote pane
    private final TreeView<FileNode>         remoteDirTree  = new TreeView<>();
    private final TableView<RemoteFileEntry> remoteFileTable = new TableView<>();

    /** Set to true to cancel an in-progress sequential transfer. */
    private volatile boolean transferCancelled;

    public SftpBrowserPane(
            ConnectionProfile connectionProfile,
            SshSession sshSession,
            SftpService sftpService,
            I18nService i18nService,
            ThemeService themeService
    ) {
        this.sshSession = sshSession;
        this.sftpService = sftpService;
        this.i18nService = i18nService;
        this.themeService = themeService;

        getStyleClass().add("workspace-panel");
        setPadding(new Insets(12));
        setCenter(buildContent());
        setBottom(buildStatusBar());

        configureLocalDirTree();
        configureLocalFileTable();
        configureRemoteDirTree();
        configureRemoteFileTable();
        setupDragDrop();

        Path localStart = Path.of(System.getProperty("user.home"));
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            loadLocalDirectoryWithDriveRoots(localStart);
        } else {
            loadLocalDirectory(localStart);
        }
        loadRemoteDirectory(Optional.ofNullable(connectionProfile.defaultRemotePath())
                .filter(p -> !p.isBlank()).orElse("."));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private VBox buildContent() {
        VBox container = new VBox(12, buildSplitPane());
        VBox.setVgrow(container.getChildren().getLast(), Priority.ALWAYS);
        return container;
    }

    private SplitPane buildSplitPane() {
        SplitPane split = new SplitPane(buildLocalPane(), buildRemotePane());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.48);
        return split;
    }

    private VBox buildPane(String headerKey, TreeView<FileNode> dirTree,
                           TableView<?> fileTable, Runnable goUp, Runnable refresh,
                           Runnable goHome,
                           javafx.beans.property.StringProperty pathProp,
                           boolean isRemote,
                           List<Button> actionButtons) {
        Label header = new Label(i18nService.get(headerKey));
        header.getStyleClass().add("sftp-pane-header");

        TextField pathField = new TextField();
        pathField.textProperty().bindBidirectional(pathProp);
        pathField.getStyleClass().add("sftp-path-field");
        HBox.setHgrow(pathField, Priority.ALWAYS);
        pathField.setOnAction(e -> {
            String path = pathField.getText().trim();
            if (path.isEmpty()) return;
            // Unbind temporarily so the field shows user input during navigation
            pathField.textProperty().unbindBidirectional(pathProp);
            if (isRemote) {
                loadRemoteDirectory(path);
            } else {
                loadLocalDirectory(Path.of(path));
            }
            pathField.textProperty().bindBidirectional(pathProp);
        });

        Button homeBtn = svgNavButton(RES_HOME, i18nService.get("sftp.goHome"));
        Button upBtn   = svgNavButton(RES_UP, "..");
        Button refBtn  = svgNavButton(RES_REFRESH, i18nService.get("action.refresh"));
        homeBtn.setOnAction(e -> goHome.run());
        upBtn.setOnAction(e -> goUp.run());
        refBtn.setOnAction(e -> refresh.run());

        HBox nav = new HBox(4);
        nav.getStyleClass().add("sftp-nav-bar");
        nav.getChildren().add(pathField);
        nav.getChildren().add(homeBtn);
        nav.getChildren().add(upBtn);
        nav.getChildren().add(refBtn);
        // Separator spacer
        Region spacer = new Region();
        spacer.setMinWidth(8);
        nav.getChildren().add(spacer);
        nav.getChildren().addAll(actionButtons);

        Label treeLabel  = new Label(i18nService.get("sftp.remote.folders"));
        Label filesLabel = new Label(i18nService.get("sftp.remote.files"));
        treeLabel.getStyleClass().add("sftp-section-label");
        filesLabel.getStyleClass().add("sftp-section-label");

        VBox treeBox  = new VBox(2, treeLabel, dirTree);
        VBox filesBox = new VBox(2, filesLabel, fileTable);
        VBox.setVgrow(dirTree, Priority.ALWAYS);
        VBox.setVgrow(fileTable, Priority.ALWAYS);

        SplitPane vert = new SplitPane(treeBox, filesBox);
        vert.setOrientation(Orientation.VERTICAL);
        vert.setDividerPositions(0.45);

        VBox box = new VBox(4, header, nav, vert);
        VBox.setVgrow(vert, Priority.ALWAYS);
        return box;
    }

    private VBox buildLocalPane() {
        Button uploadBtn = svgActionButton(RES_UPLOAD, i18nService.get("sftp.upload"));
        uploadBtn.setOnAction(e -> uploadSelected());
        Button renameBtn = svgActionButton(RES_RENAME, i18nService.get("sftp.rename"));
        renameBtn.setOnAction(e -> renameSelectedLocalFile());
        Button deleteBtn = svgActionButton(RES_DELETE, i18nService.get("sftp.delete"));
        deleteBtn.setOnAction(e -> deleteSelectedLocalFile());
        Button mkdirBtn = svgActionButton(RES_MKDIR, i18nService.get("sftp.newFolder"));
        mkdirBtn.setOnAction(e -> createLocalDirectory());
        return buildPane("sftp.local", localDirTree, localFileTable,
                this::goUpLocal,
                () -> loadLocalFilesOnly(Path.of(viewModel.localPathProperty().get())),
                this::goHomeLocal,
                viewModel.localPathProperty(), false,
                List.of(uploadBtn, renameBtn, deleteBtn, mkdirBtn));
    }

    private VBox buildRemotePane() {
        Button downloadBtn = svgActionButton(RES_DOWNLOAD, i18nService.get("sftp.download"));
        downloadBtn.setOnAction(e -> downloadSelected());
        Button renameBtn = svgActionButton(RES_RENAME, i18nService.get("sftp.rename"));
        renameBtn.setOnAction(e -> renameSelectedRemoteFile());
        Button deleteBtn = svgActionButton(RES_DELETE, i18nService.get("sftp.delete"));
        deleteBtn.setOnAction(e -> deleteSelectedRemoteFile());
        Button mkdirBtn = svgActionButton(RES_MKDIR, i18nService.get("sftp.newFolder"));
        mkdirBtn.setOnAction(e -> createRemoteDirectory());
        return buildPane("sftp.remote", remoteDirTree, remoteFileTable,
                this::goUpRemote,
                () -> loadRemoteFilesOnly(viewModel.remotePathProperty().get()),
                this::goHomeRemote,
                viewModel.remotePathProperty(), true,
                List.of(downloadBtn, renameBtn, deleteBtn, mkdirBtn));
    }

    private BorderPane buildStatusBar() {
        Label statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.transferStatusProperty());

        Label fileIndexLabel = new Label();
        fileIndexLabel.textProperty().bind(viewModel.transferFileIndexProperty());

        Label fileNameLabel = new Label();
        fileNameLabel.textProperty().bind(viewModel.transferFileNameProperty());
        fileNameLabel.setStyle("-fx-font-weight: bold;");

        Label speedLabel = new Label();
        speedLabel.textProperty().bind(viewModel.transferSpeedProperty());
        speedLabel.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 11px;");

        ProgressBar progressBar = new ProgressBar();
        progressBar.progressProperty().bind(viewModel.transferProgressProperty());
        progressBar.setPrefWidth(200);

        Button cancelBtn = new Button(i18nService.get("sftp.cancel"));
        cancelBtn.getStyleClass().add("icon-btn");
        cancelBtn.setOnAction(e -> {
            transferCancelled = true;
            viewModel.transferringProperty().set(false);
            viewModel.transferStatusProperty().set(i18nService.get("status.transferCancelled"));
        });
        cancelBtn.visibleProperty().bind(viewModel.transferringProperty());

        HBox progressBox = new HBox(8, fileIndexLabel, fileNameLabel, speedLabel, progressBar, cancelBtn);
        progressBox.visibleProperty().bind(viewModel.transferringProperty());
        progressBox.managedProperty().bind(viewModel.transferringProperty());

        BorderPane status = new BorderPane();
        status.setPadding(new Insets(12, 0, 0, 0));
        status.setLeft(statusLabel);
        status.setRight(progressBox);
        return status;
    }

    // ── Tree / Table configuration ────────────────────────────────────────────

    private void configureLocalDirTree() {
        localDirTree.setShowRoot(true);
        localDirTree.setCellFactory(tv -> new LocalDirTreeCell());
        localDirTree.setContextMenu(buildLocalTreeEmptyContextMenu());
        localDirTree.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item == null) return;
            FileNode node = item.getValue();
            if (!node.isDirectory() || node.path().isBlank()) return;
            loadLocalFilesOnly(Path.of(node.path()));
        });
        localDirTree.expandedItemCountProperty().addListener((obs, ov, nv) -> {
            TreeItem<FileNode> expanded = findRecentlyExpanded(localDirTree);
            if (expanded != null) lazyExpandLocal(expanded);
        });
    }

    private void lazyExpandLocal(TreeItem<FileNode> item) {
        if (item == null || item.getChildren().size() != 1
                || !item.getChildren().get(0).getValue().name().equals("\0")) return;
        item.getChildren().clear();
        CompletableFuture.supplyAsync(() -> scanLocalDirectory(Path.of(item.getValue().path())))
                .whenComplete((entries, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    entries.stream().filter(LocalFileEntry::directory)
                            .sorted(Comparator.comparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(e -> {
                                TreeItem<FileNode> child = new TreeItem<>(
                                        new FileNode(e.name(), e.path().toString(), true, 0, e.modifiedAt()));
                                if (hasSubDirectories(e.path())) {
                                    child.getChildren().add(placeholder());
                                }
                                item.getChildren().add(child);
                            });
                }));
    }

    private void configureLocalFileTable() {
        localFileTable.setItems(viewModel.localEntries());
        localFileTable.getStyleClass().add("sftp-file-table");
        localFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TableColumn<LocalFileEntry, LocalFileEntry> nameCol = new TableColumn<>(i18nService.get("column.name"));
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        nameCol.setCellFactory(col -> new TableCell<LocalFileEntry, LocalFileEntry>() {
            @Override protected void updateItem(LocalFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Region icon = svgIcon(item.directory() ? RES_FOLDER : RES_FILE, 13);
                Label lbl = new Label(item.name());
                lbl.getStyleClass().add("sftp-cell-name");
                HBox box = new HBox(5, icon, lbl);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });
        nameCol.setSortable(true);
        nameCol.setResizable(true);
        nameCol.prefWidthProperty().bind(localFileTable.widthProperty().multiply(0.55));
        localFileTable.getColumns().setAll(
                nameCol,
                localCol(i18nService.get("column.size"),
                        e -> e.directory() ? "" : formatSize(e.size()), 0.20),
                localCol(i18nService.get("column.modified"),
                        e -> formatTime(e.modifiedAt()), 0.25)
        );
        localFileTable.setRowFactory(tv -> {
            TableRow<LocalFileEntry> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty() && row.getItem().directory()) {
                    selectLocalTreeNode(row.getItem().path().toString());
                }
            });
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(buildLocalFileContextMenu(row)));
            return row;
        });
    }

    private void configureRemoteDirTree() {
        remoteDirTree.setShowRoot(true);
        remoteDirTree.setCellFactory(tv -> new RemoteDirTreeCell());
        remoteDirTree.setContextMenu(buildRemoteTreeEmptyContextMenu());
        remoteDirTree.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item == null) return;
            FileNode node = item.getValue();
            if (!node.isDirectory() || node.path().isBlank()) return;
            loadRemoteFilesOnly(node.path());
        });
        remoteDirTree.expandedItemCountProperty().addListener((obs, ov, nv) -> {
            TreeItem<FileNode> expanded = findRecentlyExpanded(remoteDirTree);
            if (expanded != null) lazyExpandRemote(expanded);
        });
    }

    private void lazyExpandRemote(TreeItem<FileNode> item) {
        if (item == null || item.getChildren().size() != 1
                || !item.getChildren().get(0).getValue().name().equals("\0")) return;
        item.getChildren().clear();
        sftpService.listDirectory(sshSession, item.getValue().path())
                .whenComplete((listing, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    listing.entries().stream()
                            .filter(RemoteFileEntry::isDirectory)
                            .sorted(Comparator.comparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(e -> {
                                TreeItem<FileNode> child = new TreeItem<>(
                                        new FileNode(e.name(), e.path(), true, 0, null));
                                child.getChildren().add(placeholder());
                                item.getChildren().add(child);
                            });
                }));
    }

    private void configureRemoteFileTable() {
        remoteFileTable.setItems(viewModel.remoteEntries());
        remoteFileTable.getStyleClass().add("sftp-file-table");
        remoteFileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TableColumn<RemoteFileEntry, RemoteFileEntry> nameCol = new TableColumn<>(i18nService.get("column.name"));
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        nameCol.setCellFactory(col -> new TableCell<RemoteFileEntry, RemoteFileEntry>() {
            @Override protected void updateItem(RemoteFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Region icon = svgIcon(item.isDirectory() ? RES_FOLDER : RES_FILE, 13);
                Label lbl = new Label(item.name());
                lbl.getStyleClass().add("sftp-cell-name");
                HBox box = new HBox(5, icon, lbl);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });
        nameCol.setSortable(true);
        nameCol.setResizable(true);
        nameCol.prefWidthProperty().bind(remoteFileTable.widthProperty().multiply(0.40));
        remoteFileTable.getColumns().setAll(
                nameCol,
                remoteCol(i18nService.get("column.size"),
                        e -> e.isDirectory() ? "" : formatSize(e.size()), 0.15),
                remoteCol(i18nService.get("column.permissions"),
                        RemoteFileEntry::permissionString, 0.20),
                remoteCol(i18nService.get("column.modified"),
                        e -> formatTime(e.modifiedAt()), 0.25)
        );
        remoteFileTable.setRowFactory(tv -> {
            TableRow<RemoteFileEntry> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty() && row.getItem().isDirectory()) {
                    TreeItem<FileNode> found = findTreeItem(remoteDirTree.getRoot(), row.getItem().path());
                    if (found != null) {
                        remoteDirTree.getSelectionModel().select(found);
                        found.setExpanded(true);
                    }
                }
            });
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(buildRemoteFileContextMenu(row)));
            return row;
        });
    }

    private void setupDragDrop() {
        // local file → remote
        localFileTable.setOnDragDetected(ev -> {
            List<LocalFileEntry> selected = localFileTable.getSelectionModel().getSelectedItems()
                    .stream().filter(e -> !e.directory()).toList();
            if (selected.isEmpty()) return;
            Dragboard db = localFileTable.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            cc.putFiles(selected.stream().map(e -> e.path().toFile()).toList());
            db.setContent(cc);
            ev.consume();
        });
        remoteFileTable.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        remoteFileTable.setOnDragDropped(ev -> {
            if (ev.getDragboard().hasFiles()) {
                List<LocalFileEntry> entries = ev.getDragboard().getFiles().stream()
                        .filter(f -> !f.isDirectory())
                        .map(f -> new LocalFileEntry(f.toPath(), f.getName(), false, f.length(),
                                Instant.ofEpochMilli(f.lastModified())))
                        .toList();
                if (!entries.isEmpty()) {
                    if (entries.size() == 1) uploadFile(entries.get(0).path());
                    else uploadMultipleFiles(entries);
                }
                ev.setDropCompleted(true);
            } else { ev.setDropCompleted(false); }
            ev.consume();
        });
        // remote file → local
        remoteFileTable.setOnDragDetected(ev -> {
            List<RemoteFileEntry> selected = remoteFileTable.getSelectionModel().getSelectedItems()
                    .stream().filter(e -> !e.isDirectory()).toList();
            if (selected.isEmpty()) return;
            Dragboard db = remoteFileTable.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            if (selected.size() == 1) {
                cc.putString("remote:" + selected.get(0).path());
            } else {
                cc.putString("remote-multi:" + String.join("|",
                        selected.stream().map(RemoteFileEntry::path).toList()));
            }
            db.setContent(cc);
            ev.consume();
        });
        localFileTable.setOnDragOver(ev -> {
            if (ev.getDragboard().hasString() && ev.getDragboard().getString().startsWith("remote"))
                ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        localFileTable.setOnDragDropped(ev -> {
            String v = ev.getDragboard().getString();
            if (v != null && v.startsWith("remote-multi:")) {
                String[] paths = v.substring("remote-multi:".length()).split("\\|");
                List<RemoteFileEntry> entries = List.of(paths).stream()
                        .map(p -> remoteFileTable.getItems().stream()
                                .filter(e -> e.path().equals(p)).findFirst().orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (!entries.isEmpty()) {
                    if (entries.size() == 1) downloadFile(entries.get(0).path());
                    else downloadMultipleFiles(entries);
                }
                ev.setDropCompleted(true);
            } else if (v != null && v.startsWith("remote:")) {
                downloadFile(v.substring("remote:".length()));
                ev.setDropCompleted(true);
            } else { ev.setDropCompleted(false); }
            ev.consume();
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadLocalDirectory(Path directory) {
        CompletableFuture.supplyAsync(() -> scanLocalDirectory(directory))
                .whenComplete((entries, t) -> FxThread.run(() -> {
                    if (t != null) {
                        Throwable cause = t.getCause() == null ? t : t.getCause();
                        viewModel.transferStatusProperty().set(
                                i18nService.get("status.localLoadFailed", cause.getMessage()));
                        return;
                    }
                    viewModel.setLocalEntries(directory, entries);

                    // build dir tree on first load only
                    if (localDirTree.getRoot() == null) {
                        TreeItem<FileNode> root = new TreeItem<>(
                                new FileNode(directory.getFileName() != null
                                        ? directory.getFileName().toString() : directory.toString(),
                                        directory.toString(), true, 0, null));
                        root.setExpanded(true);
                        entries.stream().filter(LocalFileEntry::directory)
                                .sorted(Comparator.comparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                                .forEach(e -> {
                                    TreeItem<FileNode> child = new TreeItem<>(
                                            new FileNode(e.name(), e.path().toString(), true, 0, e.modifiedAt()));
                                    if (hasSubDirectories(e.path())) {
                                        child.getChildren().add(placeholder());
                                    }
                                    root.getChildren().add(child);
                                });
                        localDirTree.setRoot(root);
                    }
                }));
    }

    /** Windows: build a virtual root "This PC" with each drive (C:\, D:\, …) as a child. */
    private void loadLocalDirectoryWithDriveRoots(Path initialDir) {
        File[] roots = File.listRoots();
        if (roots == null || roots.length == 0) {
            loadLocalDirectory(initialDir);
            return;
        }

        TreeItem<FileNode> virtualRoot = new TreeItem<>(
                new FileNode("This PC", "", true, 0, null));
        virtualRoot.setExpanded(true);

        TreeItem<FileNode> selectItem = null;
        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            // Skip empty/unavailable drives (e.g. A:\ on some systems)
            if (!root.exists()) continue;
            String name = rootPath.replace("\\", "/");  // "C:/"
            TreeItem<FileNode> driveItem = new TreeItem<>(
                    new FileNode(name, rootPath, true, 0, null));
            driveItem.getChildren().add(placeholder());
            virtualRoot.getChildren().add(driveItem);
            // Pre-select the drive that contains user.home
            if (selectItem == null && initialDir.getRoot().toString().equalsIgnoreCase(rootPath)) {
                selectItem = driveItem;
            }
        }

        localDirTree.setRoot(virtualRoot);

        // Expand the initial drive and select user.home
        if (selectItem != null) {
            TreeItem<FileNode> finalSelectItem = selectItem;
            selectItem.setExpanded(true);
            // Lazy-expand the drive, then select the initial directory
            lazyExpandLocal(selectItem);
            CompletableFuture.supplyAsync(() -> scanLocalDirectory(initialDir))
                    .whenComplete((entries, t) -> FxThread.run(() -> {
                        if (t != null) return;
                        viewModel.setLocalEntries(initialDir, entries);
                        // Populate drive children
                        finalSelectItem.getChildren().clear();
                        entries.stream().filter(LocalFileEntry::directory)
                                .sorted(Comparator.comparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                                .forEach(e -> {
                                    TreeItem<FileNode> child = new TreeItem<>(
                                            new FileNode(e.name(), e.path().toString(), true, 0, e.modifiedAt()));
                                    if (hasSubDirectories(e.path())) {
                                        child.getChildren().add(placeholder());
                                    }
                                    finalSelectItem.getChildren().add(child);
                                });
                        finalSelectItem.setExpanded(true);
                    }));
        }
    }

    /** Refresh only the file table for a given local path (no tree rebuild). */
    private void loadLocalFilesOnly(Path directory) {
        CompletableFuture.supplyAsync(() -> scanLocalDirectory(directory))
                .whenComplete((entries, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    viewModel.setLocalEntries(directory, entries);
                }));
    }

    /** Check if a local directory contains at least one subdirectory. */
    private static boolean hasSubDirectories(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(Files::isDirectory);
        } catch (Exception e) {
            return false;
        }
    }

    private List<LocalFileEntry> scanLocalDirectory(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> {
                try {
                    return new LocalFileEntry(path, path.getFileName().toString(),
                            Files.isDirectory(path),
                            Files.isDirectory(path) ? 0L : Files.size(path),
                            Files.getLastModifiedTime(path).toInstant());
                } catch (Exception ex) {
                    return new LocalFileEntry(path, path.getFileName().toString(),
                            Files.isDirectory(path), 0L, Instant.EPOCH);
                }
            }).sorted(Comparator.comparing(LocalFileEntry::directory).reversed()
                    .thenComparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load local directory: " + directory, ex);
        }
    }

    /** Full remote load: rebuilds dir tree (first call) + refreshes file table. */
    private void loadRemoteDirectory(String directory) {
        sftpService.listDirectory(sshSession, directory)
                .whenComplete((listing, t) -> FxThread.run(() -> {
                    if (t != null) {
                        Throwable cause = t.getCause() == null ? t : t.getCause();
                        viewModel.transferStatusProperty().set(
                                i18nService.get("status.remoteLoadFailed", cause.getMessage()));
                        return;
                    }
                    List<RemoteFileEntry> sorted = listing.entries().stream()
                            .sorted(Comparator.comparing(RemoteFileEntry::isDirectory).reversed()
                                    .thenComparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .toList();
                    viewModel.setRemoteEntries(listing.canonicalPath(), sorted);
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.remoteLoaded", listing.canonicalPath()));

                    // build dir tree on first load only
                    if (remoteDirTree.getRoot() == null) {
                        TreeItem<FileNode> root = new TreeItem<>(
                                new FileNode(listing.canonicalPath(), listing.canonicalPath(), true, 0, null));
                        root.setExpanded(true);
                        listing.entries().stream()
                                .filter(RemoteFileEntry::isDirectory)
                                .sorted(Comparator.comparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                                .forEach(e -> {
                                    TreeItem<FileNode> child = new TreeItem<>(
                                            new FileNode(e.name(), e.path(), true, 0, null));
                                    child.getChildren().add(placeholder());
                                    root.getChildren().add(child);
                                });
                        remoteDirTree.setRoot(root);
                    }
                }));
    }

    /** Refresh only the file table for a given remote path (no tree rebuild). */
    private void loadRemoteFilesOnly(String directory) {
        sftpService.listDirectory(sshSession, directory)
                .whenComplete((listing, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    List<RemoteFileEntry> sorted = listing.entries().stream()
                            .sorted(Comparator.comparing(RemoteFileEntry::isDirectory).reversed()
                                    .thenComparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .toList();
                    viewModel.setRemoteEntries(listing.canonicalPath(), sorted);
                }));
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /** Find and select a tree node matching the given path, expanding ancestors as needed. */
    private void selectLocalTreeNode(String path) {
        TreeItem<FileNode> found = findTreeItem(localDirTree.getRoot(), path);
        if (found != null) {
            localDirTree.getSelectionModel().select(found);
            // trigger lazy expansion if needed
            found.setExpanded(true);
        }
    }

    /** Find the tree item that most recently expanded by comparing expanded items before/after the count change. */
    private TreeItem<FileNode> findRecentlyExpanded(TreeView<FileNode> tree) {
        // Walk the expanded items to find one with a placeholder child
        for (TreeItem<FileNode> item : tree.getSelectionModel().getSelectedItems()) {
            if (item.isExpanded() && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue().name().equals("\0")) {
                return item;
            }
        }
        // Fallback: scan the tree for any expanded node with a placeholder
        return findExpandedPlaceholder(tree.getRoot());
    }

    private TreeItem<FileNode> findExpandedPlaceholder(TreeItem<FileNode> parent) {
        if (parent == null) return null;
        if (parent.isExpanded() && parent.getChildren().size() == 1
                && parent.getChildren().get(0).getValue().name().equals("\0")) {
            return parent;
        }
        for (TreeItem<FileNode> child : parent.getChildren()) {
            TreeItem<FileNode> found = findExpandedPlaceholder(child);
            if (found != null) return found;
        }
        return null;
    }

    private TreeItem<FileNode> findTreeItem(TreeItem<FileNode> parent, String path) {
        if (parent == null) return null;
        if (parent.getValue().path().equals(path)) return parent;
        for (TreeItem<FileNode> child : parent.getChildren()) {
            TreeItem<FileNode> found = findTreeItem(child, path);
            if (found != null) return found;
        }
        return null;
    }

    private void goUpLocal() {
        TreeItem<FileNode> selected = localDirTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getParent() != null) {
            localDirTree.getSelectionModel().select(selected.getParent());
        } else {
            Path current = Path.of(viewModel.localPathProperty().get());
            Path parent = current.getParent();
            if (parent != null && !parent.equals(current)) {
                loadLocalDirectory(parent);
            }
        }
    }

    private void goUpRemote() {
        TreeItem<FileNode> selected = remoteDirTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getParent() != null) {
            remoteDirTree.getSelectionModel().select(selected.getParent());
        } else {
            String current = viewModel.remotePathProperty().get();
            int idx = current.lastIndexOf('/');
            loadRemoteDirectory(idx > 0 ? current.substring(0, idx) : "/");
        }
    }

    private void goHomeLocal() {
        loadLocalDirectory(Path.of(System.getProperty("user.home")));
    }

    private void goHomeRemote() {
        loadRemoteDirectory(".");
    }

    // ── Transfer actions ──────────────────────────────────────────────────────

    private void uploadSelected() {
        List<LocalFileEntry> selected = localFileTable.getSelectionModel().getSelectedItems()
                .stream().filter(e -> !e.directory()).toList();
        if (selected.isEmpty()) return;
        if (selected.size() == 1) {
            uploadFile(selected.get(0).path());
            return;
        }
        uploadMultipleFiles(selected);
    }

    private void uploadFile(Path localPath) {
        transferCancelled = false;
        String target = appendRemotePath(viewModel.remotePathProperty().get(),
                localPath.getFileName().toString());
        viewModel.transferringProperty().set(true);
        viewModel.transferFileNameProperty().set(localPath.getFileName().toString());
        executeTransfer(
                sftpService.upload(sshSession,
                        new TransferRequest(localPath, target, TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                        progressListener()),
                () -> loadRemoteFilesOnly(viewModel.remotePathProperty().get()));
    }

    private void uploadMultipleFiles(List<LocalFileEntry> files) {
        transferCancelled = false;
        viewModel.transferringProperty().set(true);
        uploadSequentially(files, 0, files.size());
    }

    private void uploadSequentially(List<LocalFileEntry> files, int index, int total) {
        if (transferCancelled || index >= total) {
            FxThread.run(() -> {
                viewModel.transferringProperty().set(false);
                viewModel.transferFileIndexProperty().set("");
                viewModel.transferFileNameProperty().set("");
                if (!transferCancelled) {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.multiTransferCompleted", total));
                }
                transferCancelled = false;
                loadRemoteFilesOnly(viewModel.remotePathProperty().get());
            });
            return;
        }
        LocalFileEntry entry = files.get(index);
        FxThread.run(() -> {
            viewModel.transferFileIndexProperty().set((index + 1) + "/" + total);
            viewModel.transferFileNameProperty().set(entry.name());
            viewModel.transferProgressProperty().set(0);
        });
        String target = appendRemotePath(viewModel.remotePathProperty().get(),
                entry.path().getFileName().toString());
        sftpService.upload(sshSession,
                new TransferRequest(entry.path(), target, TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                progressListener())
            .whenComplete((u, t) -> {
                if (t != null) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    if (!(cause instanceof TransferCancelledException)) {
                        FxThread.run(() -> viewModel.transferStatusProperty().set(
                                i18nService.get("status.transferFailed", cause.getMessage())));
                    }
                }
                uploadSequentially(files, index + 1, total);
            });
    }

    private void downloadSelected() {
        List<RemoteFileEntry> selected = remoteFileTable.getSelectionModel().getSelectedItems()
                .stream().filter(e -> !e.isDirectory()).toList();
        if (selected.isEmpty()) return;
        if (selected.size() == 1) {
            downloadFile(selected.get(0).path());
            return;
        }
        downloadMultipleFiles(selected);
    }

    private void downloadFile(String remotePath) {
        transferCancelled = false;
        Path localTarget = Path.of(viewModel.localPathProperty().get(),
                remotePath.substring(remotePath.lastIndexOf('/') + 1));
        viewModel.transferringProperty().set(true);
        viewModel.transferFileNameProperty().set(localTarget.getFileName().toString());
        executeTransfer(
                sftpService.download(sshSession,
                        new TransferRequest(localTarget, remotePath, TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                        progressListener()),
                () -> loadLocalFilesOnly(Path.of(viewModel.localPathProperty().get())));
    }

    private void downloadMultipleFiles(List<RemoteFileEntry> files) {
        transferCancelled = false;
        viewModel.transferringProperty().set(true);
        downloadSequentially(files, 0, files.size());
    }

    private void downloadSequentially(List<RemoteFileEntry> files, int index, int total) {
        if (transferCancelled || index >= total) {
            FxThread.run(() -> {
                viewModel.transferringProperty().set(false);
                viewModel.transferFileIndexProperty().set("");
                viewModel.transferFileNameProperty().set("");
                if (!transferCancelled) {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.multiTransferCompleted", total));
                }
                transferCancelled = false;
                loadLocalFilesOnly(Path.of(viewModel.localPathProperty().get()));
            });
            return;
        }
        RemoteFileEntry entry = files.get(index);
        FxThread.run(() -> {
            viewModel.transferFileIndexProperty().set((index + 1) + "/" + total);
            viewModel.transferFileNameProperty().set(entry.name());
            viewModel.transferProgressProperty().set(0);
        });
        Path localTarget = Path.of(viewModel.localPathProperty().get(), entry.name());
        sftpService.download(sshSession,
                new TransferRequest(localTarget, entry.path(), TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                progressListener())
            .whenComplete((u, t) -> {
                if (t != null) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    if (!(cause instanceof TransferCancelledException)) {
                        FxThread.run(() -> viewModel.transferStatusProperty().set(
                                i18nService.get("status.transferFailed", cause.getMessage())));
                    }
                }
                downloadSequentially(files, index + 1, total);
            });
    }

    private void renameSelectedRemoteFile() {
        RemoteFileEntry e = remoteFileTable.getSelectionModel().getSelectedItem();
        if (e == null) return;
        TextInputDialog dlg = new TextInputDialog(e.name());
        dlg.setTitle(i18nService.get("sftp.rename"));
        dlg.setHeaderText(i18nService.get("sftp.rename.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank()).ifPresent(newName -> {
            String target = appendRemotePath(viewModel.remotePathProperty().get(), newName);
            sftpService.rename(sshSession, e.path(), target)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.renameFailed", t.getMessage()));
                        else loadRemoteFilesOnly(viewModel.remotePathProperty().get());
                    }));
        });
    }

    private void deleteSelectedRemoteFile() {
        List<RemoteFileEntry> selected = remoteFileTable.getSelectionModel().getSelectedItems()
                .stream().toList();
        if (selected.isEmpty()) return;
        String msg = selected.size() == 1
                ? i18nService.get("sftp.delete.confirm", selected.get(0).name())
                : i18nService.get("sftp.delete.confirmMulti", selected.size());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(i18nService.get("sftp.delete.header"));
        themeService.applyToDialog(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused -> {
            for (RemoteFileEntry e : selected) {
                sftpService.delete(sshSession, e.path(), e.isDirectory())
                        .whenComplete((u, t) -> FxThread.run(() -> {
                            if (t != null) viewModel.transferStatusProperty().set(
                                    i18nService.get("status.deleteFailed", t.getMessage()));
                            else loadRemoteFilesOnly(viewModel.remotePathProperty().get());
                        }));
            }
        });
    }

    private void createLocalDirectory() {
        String parentPath = getSelectedLocalTreePath();
        createLocalSubDirectory(parentPath);
    }

    private String getSelectedLocalTreePath() {
        TreeItem<FileNode> selected = localDirTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null
                && selected.getValue().isDirectory() && !selected.getValue().path().isBlank()) {
            return selected.getValue().path();
        }
        return viewModel.localPathProperty().get();
    }

    private String getSelectedRemoteTreePath() {
        TreeItem<FileNode> selected = remoteDirTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null
                && selected.getValue().isDirectory() && !selected.getValue().path().isBlank()) {
            return selected.getValue().path();
        }
        return viewModel.remotePathProperty().get();
    }

    private void deleteSelectedLocalFile() {
        List<LocalFileEntry> selected = localFileTable.getSelectionModel().getSelectedItems()
                .stream().toList();
        if (selected.isEmpty()) return;
        String msg = selected.size() == 1
                ? i18nService.get("sftp.delete.confirm", selected.get(0).name())
                : i18nService.get("sftp.delete.confirmMulti", selected.size());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(i18nService.get("sftp.delete.header"));
        themeService.applyToDialog(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused -> {
            for (LocalFileEntry e : selected) {
                try {
                    if (e.directory()) {
                        deleteLocalDirectory(e.path().toFile());
                    } else {
                        Files.deleteIfExists(e.path());
                    }
                } catch (Exception ex) {
                    FxThread.run(() -> viewModel.transferStatusProperty().set(
                            i18nService.get("status.deleteFailed", ex.getMessage())));
                }
            }
            loadLocalFilesOnly(Path.of(viewModel.localPathProperty().get()));
        });
    }

    private void deleteLocalDirectory(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteLocalDirectory(child);
                else child.delete();
            }
        }
        dir.delete();
    }

    private void renameSelectedLocalFile() {
        LocalFileEntry e = localFileTable.getSelectionModel().getSelectedItem();
        if (e == null) return;
        TextInputDialog dlg = new TextInputDialog(e.name());
        dlg.setTitle(i18nService.get("sftp.rename"));
        dlg.setHeaderText(i18nService.get("sftp.rename.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank() && !n.equals(e.name())).ifPresent(newName -> {
            Path newPath = e.path().resolveSibling(newName);
            try {
                Files.move(e.path(), newPath);
                loadLocalFilesOnly(Path.of(viewModel.localPathProperty().get()));
            } catch (Exception ex) {
                viewModel.transferStatusProperty().set(
                        i18nService.get("status.renameFailed", ex.getMessage()));
            }
        });
    }

    private ContextMenu buildLocalFileContextMenu(TableRow<LocalFileEntry> row) {
        MenuItem upload = new MenuItem(i18nService.get("sftp.upload"));
        upload.setOnAction(e -> uploadSelected());
        upload.setDisable(true);
        MenuItem rename = new MenuItem(i18nService.get("sftp.rename"));
        rename.setOnAction(e -> renameSelectedLocalFile());
        MenuItem delete = new MenuItem(i18nService.get("sftp.delete"));
        delete.setOnAction(e -> deleteSelectedLocalFile());
        ContextMenu menu = new ContextMenu(upload, rename, new SeparatorMenuItem(), delete);
        menu.setOnShowing(e -> {
            LocalFileEntry item = row.getItem();
            upload.setDisable(item == null || item.directory());
        });
        return menu;
    }

    private ContextMenu buildRemoteFileContextMenu(TableRow<RemoteFileEntry> row) {
        MenuItem download = new MenuItem(i18nService.get("sftp.download"));
        download.setOnAction(e -> downloadSelected());
        download.setDisable(true);
        MenuItem rename = new MenuItem(i18nService.get("sftp.rename"));
        rename.setOnAction(e -> renameSelectedRemoteFile());
        MenuItem delete = new MenuItem(i18nService.get("sftp.delete"));
        delete.setOnAction(e -> deleteSelectedRemoteFile());
        ContextMenu menu = new ContextMenu(download, rename, new SeparatorMenuItem(), delete);
        menu.setOnShowing(e -> {
            RemoteFileEntry item = row.getItem();
            download.setDisable(item == null || item.isDirectory());
        });
        return menu;
    }

    private void createRemoteDirectory() {
        createRemoteSubDirectory(getSelectedRemoteTreePath());
    }

    private void createRemoteSubDirectory(String parentPath) {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle(i18nService.get("sftp.newFolder"));
        dlg.setHeaderText(i18nService.get("sftp.newFolder.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank()).ifPresent(name -> {
            String fullPath = appendRemotePath(parentPath, name);
            boolean exists = viewModel.remoteEntries().stream()
                    .anyMatch(e -> e.name().equals(name));
            if (exists) {
                Alert alert = new Alert(Alert.AlertType.WARNING,
                        i18nService.get("sftp.newFolder.exists", name));
                themeService.applyToDialog(alert);
                alert.showAndWait();
                return;
            }
            sftpService.createDirectory(sshSession, fullPath, false)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.mkdirFailed", t.getMessage()));
                        else {
                            refreshRemoteTreeFolder(parentPath);
                            loadRemoteFilesOnly(parentPath);
                        }
                    }));
        });
    }

    private void renameRemoteFolder(FileNode folder) {
        TextInputDialog dlg = new TextInputDialog(folder.name());
        dlg.setTitle(i18nService.get("sftp.rename"));
        dlg.setHeaderText(i18nService.get("sftp.renameFolder.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank() && !n.equals(folder.name())).ifPresent(newName -> {
            int lastSlash = folder.path().lastIndexOf('/');
            String parentPath = lastSlash > 0 ? folder.path().substring(0, lastSlash) : "/";
            String targetPath = appendRemotePath(parentPath, newName);
            sftpService.rename(sshSession, folder.path(), targetPath)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.renameFailed", t.getMessage()));
                        else {
                            refreshRemoteTreeFolder(parentPath);
                            loadRemoteFilesOnly(parentPath);
                        }
                    }));
        });
    }

    private void deleteRemoteFolder(FileNode folder) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                i18nService.get("sftp.deleteFolder.confirm", folder.name()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(i18nService.get("sftp.deleteFolder.header"));
        themeService.applyToDialog(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused -> {
            sftpService.delete(sshSession, folder.path(), true)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.deleteFailed", t.getMessage()));
                        else {
                            int lastSlash = folder.path().lastIndexOf('/');
                            String parentPath = lastSlash > 0 ? folder.path().substring(0, lastSlash) : "/";
                            refreshRemoteTreeFolder(parentPath);
                            loadRemoteFilesOnly(parentPath);
                        }
                    }));
        });
    }

    private void refreshRemoteTreeFolder(String parentPath) {
        TreeItem<FileNode> parentItem = findTreeItem(remoteDirTree.getRoot(), parentPath);
        if (parentItem == null) return;
        parentItem.getChildren().clear();
        sftpService.listDirectory(sshSession, parentPath)
                .whenComplete((listing, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    listing.entries().stream()
                            .filter(RemoteFileEntry::isDirectory)
                            .sorted(Comparator.comparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(e -> {
                                TreeItem<FileNode> child = new TreeItem<>(
                                        new FileNode(e.name(), e.path(), true, 0, null));
                                child.getChildren().add(placeholder());
                                parentItem.getChildren().add(child);
                            });
                }));
    }

    private ContextMenu buildLocalTreeEmptyContextMenu() {
        MenuItem newFolder = new MenuItem(i18nService.get("sftp.newFolder"));
        newFolder.setOnAction(e -> createLocalDirectory());
        return new ContextMenu(newFolder);
    }

    private ContextMenu buildLocalFolderContextMenu(FileNode folder) {
        MenuItem newFolder = new MenuItem(i18nService.get("sftp.newFolder"));
        newFolder.setOnAction(e -> createLocalSubDirectory(folder.path()));
        MenuItem rename = new MenuItem(i18nService.get("sftp.rename"));
        rename.setOnAction(e -> renameLocalFolder(folder));
        MenuItem delete = new MenuItem(i18nService.get("sftp.delete"));
        delete.setOnAction(e -> deleteLocalFolder(folder));
        return new ContextMenu(newFolder, rename, new SeparatorMenuItem(), delete);
    }

    private void createLocalSubDirectory(String parentPath) {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle(i18nService.get("sftp.newFolder"));
        dlg.setHeaderText(i18nService.get("sftp.newFolder.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank()).ifPresent(name -> {
            Path dir = Path.of(parentPath, name);
            if (Files.exists(dir)) {
                Alert alert = new Alert(Alert.AlertType.WARNING,
                        i18nService.get("sftp.newFolder.exists", name));
                themeService.applyToDialog(alert);
                alert.showAndWait();
                return;
            }
            try {
                Files.createDirectory(dir);
                refreshLocalTreeFolder(parentPath);
                loadLocalFilesOnly(Path.of(parentPath));
            } catch (Exception ex) {
                viewModel.transferStatusProperty().set(
                        i18nService.get("status.mkdirFailed", ex.getMessage()));
            }
        });
    }

    private void renameLocalFolder(FileNode folder) {
        TextInputDialog dlg = new TextInputDialog(folder.name());
        dlg.setTitle(i18nService.get("sftp.rename"));
        dlg.setHeaderText(i18nService.get("sftp.renameFolder.prompt"));
        themeService.applyToDialog(dlg);
        dlg.showAndWait().filter(n -> !n.isBlank() && !n.equals(folder.name())).ifPresent(newName -> {
            Path newPath = Path.of(folder.path()).resolveSibling(newName);
            try {
                Files.move(Path.of(folder.path()), newPath);
                refreshLocalTreeFolder(Path.of(folder.path()).getParent().toString());
                loadLocalFilesOnly(Path.of(folder.path()).getParent());
            } catch (Exception ex) {
                viewModel.transferStatusProperty().set(
                        i18nService.get("status.renameFailed", ex.getMessage()));
            }
        });
    }

    private void deleteLocalFolder(FileNode folder) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                i18nService.get("sftp.deleteFolder.confirm", folder.name()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(i18nService.get("sftp.deleteFolder.header"));
        themeService.applyToDialog(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused -> {
            try {
                deleteLocalDirectory(Path.of(folder.path()).toFile());
                String parentPath = Path.of(folder.path()).getParent().toString();
                refreshLocalTreeFolder(parentPath);
                loadLocalFilesOnly(Path.of(parentPath));
            } catch (Exception ex) {
                viewModel.transferStatusProperty().set(
                        i18nService.get("status.deleteFailed", ex.getMessage()));
            }
        });
    }

    private void refreshLocalTreeFolder(String parentPath) {
        TreeItem<FileNode> parentItem = findTreeItem(localDirTree.getRoot(), parentPath);
        if (parentItem == null) return;
        parentItem.getChildren().clear();
        CompletableFuture.supplyAsync(() -> scanLocalDirectory(Path.of(parentPath)))
                .whenComplete((entries, t) -> FxThread.run(() -> {
                    if (t != null) return;
                    entries.stream().filter(LocalFileEntry::directory)
                            .sorted(Comparator.comparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(e -> {
                                TreeItem<FileNode> child = new TreeItem<>(
                                        new FileNode(e.name(), e.path().toString(), true, 0, e.modifiedAt()));
                                if (hasSubDirectories(e.path())) {
                                    child.getChildren().add(placeholder());
                                }
                                parentItem.getChildren().add(child);
                            });
                }));
    }

    private ContextMenu buildRemoteTreeEmptyContextMenu() {
        MenuItem newFolder = new MenuItem(i18nService.get("sftp.newFolder"));
        newFolder.setOnAction(e -> createRemoteDirectory());
        return new ContextMenu(newFolder);
    }

    private ContextMenu buildRemoteFolderContextMenu(FileNode folder) {
        MenuItem newFolder = new MenuItem(i18nService.get("sftp.newFolder"));
        newFolder.setOnAction(e -> createRemoteSubDirectory(folder.path()));
        MenuItem rename = new MenuItem(i18nService.get("sftp.rename"));
        rename.setOnAction(e -> renameRemoteFolder(folder));
        MenuItem delete = new MenuItem(i18nService.get("sftp.delete"));
        delete.setOnAction(e -> deleteRemoteFolder(folder));
        return new ContextMenu(newFolder, rename, new SeparatorMenuItem(), delete);
    }

    // ── Column helpers ────────────────────────────────────────────────────────

    private TableColumn<LocalFileEntry, String> localCol(
            String title, Function<LocalFileEntry, String> mapper, double ratio) {
        TableColumn<LocalFileEntry, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(mapper.apply(c.getValue())));
        col.setSortable(true);
        col.setResizable(true);
        col.prefWidthProperty().bind(localFileTable.widthProperty().multiply(ratio));
        return col;
    }

    private TableColumn<RemoteFileEntry, String> remoteCol(
            String title, Function<RemoteFileEntry, String> mapper, double ratio) {
        TableColumn<RemoteFileEntry, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(mapper.apply(c.getValue())));
        col.setSortable(true);
        col.setResizable(true);
        col.prefWidthProperty().bind(remoteFileTable.widthProperty().multiply(ratio));
        return col;
    }

    // ── Transfer helpers ──────────────────────────────────────────────────────

    private TransferProgressListener progressListener() {
        final long[] lastBytes = {0};
        final long[] lastTime = {0};
        return new TransferProgressListener() {
            @Override public void onStarted(TransferProgress p) {
                lastBytes[0] = p.transferredBytes();
                lastTime[0] = System.currentTimeMillis();
                FxThread.run(() -> {
                    viewModel.transferringProperty().set(true);
                    viewModel.transferFileNameProperty().set(extractTransferName(p));
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.transferStarted", p.source(), p.target()));
                    viewModel.transferProgressProperty().set(p.progressRatio());
                    viewModel.transferSpeedProperty().set("");
                });
            }
            @Override public void onProgress(TransferProgress p) {
                long now = System.currentTimeMillis();
                long elapsed = now - lastTime[0];
                long delta = p.transferredBytes() - lastBytes[0];
                final String speedText;
                if (elapsed > 200 && delta > 0) {
                    speedText = formatSpeed(delta * 1000.0 / elapsed);
                    lastBytes[0] = p.transferredBytes();
                    lastTime[0] = now;
                } else {
                    speedText = null;
                }
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(i18nService.get("status.transferRunning",
                            formatSize(p.transferredBytes()), formatSize(p.totalBytes())));
                    viewModel.transferProgressProperty().set(p.progressRatio());
                    if (speedText != null) {
                        viewModel.transferSpeedProperty().set(speedText);
                    }
                });
            }
            @Override public void onCompleted(TransferProgress p) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(i18nService.get("status.transferCompleted"));
                    viewModel.transferProgressProperty().set(1.0);
                    viewModel.transferSpeedProperty().set("");
                });
            }
            @Override public void onFailed(TransferProgress p, Throwable t) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.transferFailed", t.getMessage()));
                    viewModel.transferProgressProperty().set(0);
                    viewModel.transferSpeedProperty().set("");
                });
            }
            @Override public boolean isCancelled() {
                return transferCancelled;
            }
        };
    }

    private String extractTransferName(TransferProgress p) {
        String source = p.source();
        if (p.direction() == com.jlshell.sftp.model.TransferDirection.UPLOAD) {
            int sep = source.lastIndexOf(File.separatorChar);
            return sep >= 0 ? source.substring(sep + 1) : source;
        } else {
            int sep = source.lastIndexOf('/');
            return sep >= 0 ? source.substring(sep + 1) : source;
        }
    }

    private void executeTransfer(CompletableFuture<Void> future, Runnable onSuccess) {
        future.whenComplete((u, t) -> FxThread.run(() -> {
            if (t != null) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                if (cause instanceof TransferCancelledException) {
                    viewModel.transferStatusProperty().set(i18nService.get("status.transferCancelled"));
                } else {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.transferFailed", cause.getMessage()));
                }
            } else {
                onSuccess.run();
            }
            viewModel.transferringProperty().set(false);
            viewModel.transferSpeedProperty().set("");
        }));
    }

    private String appendRemotePath(String dir, String name) {
        if (dir == null || dir.isBlank() || ".".equals(dir)) return name;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format("%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        if (bytesPerSecond < 1024L * 1024L * 1024L) return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        return String.format("%.1f GB/s", bytesPerSecond / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatTime(Instant instant) {
        return instant == null || instant.equals(Instant.EPOCH) ? "" : TIME_FMT.format(instant);
    }

    /** Creates a themed SVG icon Region loaded from a resource file. */
    private static Region svgIcon(String resourcePath, double size) {
        return loadSvgShape(resourcePath, size);
    }

    private static TreeItem<FileNode> placeholder() {
        return new TreeItem<>(new FileNode("\0", "", true, 0, null));
    }

    // ── SVG icon button (same style as sidebar action bar) ────────────────────

    private Button svgActionButton(String resourcePath, String tooltip) {
        Region icon = loadSvgShape(resourcePath, 14);
        Button btn = new Button();
        btn.setGraphic(icon);
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        btn.getStyleClass().add("icon-btn");
        return btn;
    }

    private Button svgNavButton(String resourcePath, String tooltip) {
        Button btn = new Button();
        btn.setGraphic(loadSvgShape(resourcePath, 13));
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        btn.getStyleClass().add("icon-btn");
        return btn;
    }

    /** 从 SVG 文件提取 path 数据，返回 Region（通过 SVGPath shape 显示） */
    private static Region loadSvgShape(String resourcePath, double size) {
        try (var is = SftpBrowserPane.class.getResourceAsStream(resourcePath)) {
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
            region.getStyleClass().add("action-bar-icon");
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

    // ── Inner types ───────────────────────────────────────────────────────────

    record FileNode(String name, String path, boolean isDirectory, long size, Instant modifiedAt) {
        @Override public String toString() { return name; }
    }

    private class LocalDirTreeCell extends TreeCell<FileNode> {
        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.name().equals("\0")) {
                setText(null); setGraphic(null); setContextMenu(null); return;
            }
            Region icon = svgIcon(RES_FOLDER, 13);
            Label lbl = new Label(item.name());
            HBox box = new HBox(5, icon, lbl);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(box); setText(null);
            setContextMenu(buildLocalFolderContextMenu(item));
        }
    }

    private class RemoteDirTreeCell extends TreeCell<FileNode> {
        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.name().equals("\0")) {
                setText(null); setGraphic(null); setContextMenu(null); return;
            }
            Region icon = svgIcon(RES_FOLDER, 13);
            Label lbl = new Label(item.name());
            HBox box = new HBox(5, icon, lbl);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(box); setText(null);
            setContextMenu(buildRemoteFolderContextMenu(item));
        }
    }
}
