package com.jlshell.ui.view;

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
import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.sftp.model.TransferProgress;
import com.jlshell.sftp.model.TransferRequest;
import com.jlshell.sftp.model.TransferResumeMode;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.service.TransferProgressListener;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.LocalFileEntry;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.viewmodel.SftpBrowserViewModel;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    // Lucide SVG paths (same as SidebarTreeView)
    private static final String ICON_FOLDER   = "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z";
    private static final String ICON_FILE     = "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7ZM14 2v4a1 1 0 0 0 1 1h4";
    private static final String ICON_UP       = "M12 19V5M5 12l7-7 7 7";
    private static final String ICON_REFRESH  = "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15";

    private final SshSession sshSession;
    private final SftpService sftpService;
    private final I18nService i18nService;
    private final SftpBrowserViewModel viewModel = new SftpBrowserViewModel();

    // Local pane
    private final TreeView<FileNode>        localDirTree  = new TreeView<>();
    private final TableView<LocalFileEntry> localFileTable = new TableView<>();

    // Remote pane
    private final TreeView<FileNode>         remoteDirTree  = new TreeView<>();
    private final TableView<RemoteFileEntry> remoteFileTable = new TableView<>();

    public SftpBrowserPane(
            ConnectionProfile connectionProfile,
            SshSession sshSession,
            SftpService sftpService,
            I18nService i18nService
    ) {
        this.sshSession = sshSession;
        this.sftpService = sftpService;
        this.i18nService = i18nService;

        getStyleClass().add("workspace-panel");
        setPadding(new Insets(12));
        setCenter(buildContent());
        setBottom(buildStatusBar());

        configureLocalDirTree();
        configureLocalFileTable();
        configureRemoteDirTree();
        configureRemoteFileTable();
        setupDragDrop();

        loadLocalDirectory(Path.of(System.getProperty("user.home")));
        loadRemoteDirectory(Optional.ofNullable(connectionProfile.defaultRemotePath())
                .filter(p -> !p.isBlank()).orElse("."));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private VBox buildContent() {
        VBox container = new VBox(12, buildToolbar(), buildSplitPane());
        VBox.setVgrow(container.getChildren().getLast(), Priority.ALWAYS);
        return container;
    }

    private HBox buildToolbar() {
        Button upload   = new Button(i18nService.get("sftp.upload"));
        Button download = new Button(i18nService.get("sftp.download"));
        Button rename   = new Button(i18nService.get("sftp.rename"));
        Button delete   = new Button(i18nService.get("sftp.delete"));
        Button mkdir    = new Button(i18nService.get("sftp.newFolder"));
        upload.setOnAction(e -> uploadSelected());
        download.setOnAction(e -> downloadSelected());
        rename.setOnAction(e -> renameSelectedRemoteFile());
        delete.setOnAction(e -> deleteSelectedRemoteFile());
        mkdir.setOnAction(e -> createRemoteDirectory());
        HBox bar = new HBox(8, upload, download, rename, delete, mkdir);
        bar.getStyleClass().add("toolbar-strip");
        return bar;
    }

    private SplitPane buildSplitPane() {
        SplitPane split = new SplitPane(buildLocalPane(), buildRemotePane());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.48);
        return split;
    }

    private VBox buildPane(String headerKey, TreeView<FileNode> dirTree,
                           TableView<?> fileTable, Runnable goUp, Runnable refresh,
                           javafx.beans.property.StringProperty pathProp) {
        Label header = new Label(i18nService.get(headerKey));
        header.getStyleClass().add("sftp-pane-header");

        Label pathLabel = new Label();
        pathLabel.textProperty().bind(pathProp);
        pathLabel.getStyleClass().add("sftp-path-label");
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        Button upBtn  = svgNavButton(ICON_UP, "..");
        Button refBtn = svgNavButton(ICON_REFRESH, i18nService.get("action.refresh"));
        upBtn.setOnAction(e -> goUp.run());
        refBtn.setOnAction(e -> refresh.run());

        HBox nav = new HBox(4, pathLabel, upBtn, refBtn);
        nav.getStyleClass().add("sftp-nav-bar");

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
        return buildPane("sftp.local", localDirTree, localFileTable,
                this::goUpLocal,
                () -> loadLocalDirectory(Path.of(viewModel.localPathProperty().get())),
                viewModel.localPathProperty());
    }

    private VBox buildRemotePane() {
        return buildPane("sftp.remote", remoteDirTree, remoteFileTable,
                this::goUpRemote,
                () -> loadRemoteDirectory(viewModel.remotePathProperty().get()),
                viewModel.remotePathProperty());
    }

    private BorderPane buildStatusBar() {
        ProgressBar bar = new ProgressBar();
        bar.progressProperty().bind(viewModel.transferProgressProperty());
        bar.setPrefWidth(180);
        Label lbl = new Label();
        lbl.textProperty().bind(viewModel.transferStatusProperty());
        BorderPane status = new BorderPane();
        status.setPadding(new Insets(12, 0, 0, 0));
        status.setLeft(lbl);
        status.setRight(bar);
        return status;
    }

    // ── Tree / Table configuration ────────────────────────────────────────────

    private void configureLocalDirTree() {
        localDirTree.setShowRoot(false);
        localDirTree.setCellFactory(tv -> new DirTreeCell());
        localDirTree.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item != null && item.getValue().isDirectory() && !item.getValue().path().isBlank()) {
                loadLocalDirectory(Path.of(item.getValue().path()));
            }
        });
    }

    private void configureLocalFileTable() {
        localFileTable.setItems(viewModel.localEntries());
        localFileTable.getStyleClass().add("sftp-file-table");
        TableColumn<LocalFileEntry, LocalFileEntry> nameCol = new TableColumn<>(i18nService.get("column.name"));
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        nameCol.setCellFactory(col -> new TableCell<LocalFileEntry, LocalFileEntry>() {
            @Override protected void updateItem(LocalFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Region icon = svgIcon(item.directory() ? ICON_FOLDER : ICON_FILE, 13);
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
                    loadLocalDirectory(row.getItem().path());
                }
            });
            return row;
        });
    }

    private void configureRemoteDirTree() {
        remoteDirTree.setShowRoot(true);
        remoteDirTree.setCellFactory(tv -> new DirTreeCell());
        remoteDirTree.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item == null) return;
            FileNode node = item.getValue();
            if (!node.isDirectory() || node.path().isBlank()) return;
            // load file list for selected dir
            loadRemoteFilesOnly(node.path());
            // lazy-expand: if placeholder child present, load subdirs
            if (item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue().name().equals("\0")) {
                item.getChildren().clear();
                sftpService.listDirectory(sshSession, node.path())
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
        });
    }

    private void configureRemoteFileTable() {
        remoteFileTable.setItems(viewModel.remoteEntries());
        remoteFileTable.getStyleClass().add("sftp-file-table");
        TableColumn<RemoteFileEntry, RemoteFileEntry> nameCol = new TableColumn<>(i18nService.get("column.name"));
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        nameCol.setCellFactory(col -> new TableCell<RemoteFileEntry, RemoteFileEntry>() {
            @Override protected void updateItem(RemoteFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Region icon = svgIcon(item.isDirectory() ? ICON_FOLDER : ICON_FILE, 13);
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
                    loadRemoteDirectory(row.getItem().path());
                }
            });
            return row;
        });
    }

    private void setupDragDrop() {
        // local file → remote
        localFileTable.setOnDragDetected(ev -> {
            LocalFileEntry e = localFileTable.getSelectionModel().getSelectedItem();
            if (e == null || e.directory()) return;
            Dragboard db = localFileTable.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            cc.putFiles(List.of(e.path().toFile()));
            db.setContent(cc);
            ev.consume();
        });
        remoteFileTable.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        remoteFileTable.setOnDragDropped(ev -> {
            if (ev.getDragboard().hasFiles()) {
                ev.getDragboard().getFiles().forEach(f -> uploadFile(f.toPath()));
                ev.setDropCompleted(true);
            } else { ev.setDropCompleted(false); }
            ev.consume();
        });
        // remote file → local
        remoteFileTable.setOnDragDetected(ev -> {
            RemoteFileEntry e = remoteFileTable.getSelectionModel().getSelectedItem();
            if (e == null || e.isDirectory()) return;
            Dragboard db = remoteFileTable.startDragAndDrop(TransferMode.COPY);
            ClipboardContent cc = new ClipboardContent();
            cc.putString("remote:" + e.path());
            db.setContent(cc);
            ev.consume();
        });
        localFileTable.setOnDragOver(ev -> {
            if (ev.getDragboard().hasString() && ev.getDragboard().getString().startsWith("remote:"))
                ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        localFileTable.setOnDragDropped(ev -> {
            String v = ev.getDragboard().getString();
            if (v != null && v.startsWith("remote:")) {
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

                    // rebuild dir tree root
                    TreeItem<FileNode> root = new TreeItem<>(
                            new FileNode(directory.getFileName() != null
                                    ? directory.getFileName().toString() : directory.toString(),
                                    directory.toString(), true, 0, null));
                    root.setExpanded(true);
                    entries.stream().filter(LocalFileEntry::directory).forEach(e -> {
                        TreeItem<FileNode> child = new TreeItem<>(
                                new FileNode(e.name(), e.path().toString(), true, 0, e.modifiedAt()));
                        root.getChildren().add(child);
                    });
                    localDirTree.setRoot(root);
                }));
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

    private void goUpLocal() {
        Path current = Path.of(viewModel.localPathProperty().get());
        if (current.getParent() != null) loadLocalDirectory(current.getParent());
    }

    private void goUpRemote() {
        String current = viewModel.remotePathProperty().get();
        int idx = current.lastIndexOf('/');
        loadRemoteDirectory(idx > 0 ? current.substring(0, idx) : "/");
    }

    // ── Transfer actions ──────────────────────────────────────────────────────

    private void uploadSelected() {
        LocalFileEntry e = localFileTable.getSelectionModel().getSelectedItem();
        if (e == null || e.directory()) return;
        uploadFile(e.path());
    }

    private void uploadFile(Path localPath) {
        String target = appendRemotePath(viewModel.remotePathProperty().get(),
                localPath.getFileName().toString());
        executeTransfer(
                sftpService.upload(sshSession,
                        new TransferRequest(localPath, target, TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                        progressListener()),
                () -> loadRemoteDirectory(viewModel.remotePathProperty().get()));
    }

    private void downloadSelected() {
        RemoteFileEntry e = remoteFileTable.getSelectionModel().getSelectedItem();
        if (e == null || e.isDirectory()) return;
        downloadFile(e.path());
    }

    private void downloadFile(String remotePath) {
        Path localTarget = Path.of(viewModel.localPathProperty().get(),
                remotePath.substring(remotePath.lastIndexOf('/') + 1));
        executeTransfer(
                sftpService.download(sshSession,
                        new TransferRequest(localTarget, remotePath, TransferResumeMode.RESUME_IF_POSSIBLE, 64 * 1024),
                        progressListener()),
                () -> loadLocalDirectory(Path.of(viewModel.localPathProperty().get())));
    }

    private void renameSelectedRemoteFile() {
        RemoteFileEntry e = remoteFileTable.getSelectionModel().getSelectedItem();
        if (e == null) return;
        TextInputDialog dlg = new TextInputDialog(e.name());
        dlg.setTitle(i18nService.get("sftp.rename"));
        dlg.setHeaderText(i18nService.get("sftp.rename.prompt"));
        dlg.showAndWait().filter(n -> !n.isBlank()).ifPresent(newName -> {
            String target = appendRemotePath(viewModel.remotePathProperty().get(), newName);
            sftpService.rename(sshSession, e.path(), target)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.renameFailed", t.getMessage()));
                        else loadRemoteDirectory(viewModel.remotePathProperty().get());
                    }));
        });
    }

    private void deleteSelectedRemoteFile() {
        RemoteFileEntry e = remoteFileTable.getSelectionModel().getSelectedItem();
        if (e == null) return;
        sftpService.delete(sshSession, e.path(), e.isDirectory())
                .whenComplete((u, t) -> FxThread.run(() -> {
                    if (t != null) viewModel.transferStatusProperty().set(
                            i18nService.get("status.deleteFailed", t.getMessage()));
                    else loadRemoteDirectory(viewModel.remotePathProperty().get());
                }));
    }

    private void createRemoteDirectory() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle(i18nService.get("sftp.newFolder"));
        dlg.setHeaderText(i18nService.get("sftp.newFolder.prompt"));
        dlg.showAndWait().filter(n -> !n.isBlank()).ifPresent(name -> {
            sftpService.createDirectory(sshSession,
                    appendRemotePath(viewModel.remotePathProperty().get(), name), false)
                    .whenComplete((u, t) -> FxThread.run(() -> {
                        if (t != null) viewModel.transferStatusProperty().set(
                                i18nService.get("status.mkdirFailed", t.getMessage()));
                        else loadRemoteDirectory(viewModel.remotePathProperty().get());
                    }));
        });
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
        return new TransferProgressListener() {
            @Override public void onStarted(TransferProgress p) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.transferStarted", p.source(), p.target()));
                    viewModel.transferProgressProperty().set(p.progressRatio());
                });
            }
            @Override public void onProgress(TransferProgress p) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(i18nService.get("status.transferRunning",
                            formatSize(p.transferredBytes()), formatSize(p.totalBytes())));
                    viewModel.transferProgressProperty().set(p.progressRatio());
                });
            }
            @Override public void onCompleted(TransferProgress p) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(i18nService.get("status.transferCompleted"));
                    viewModel.transferProgressProperty().set(1.0);
                });
            }
            @Override public void onFailed(TransferProgress p, Throwable t) {
                FxThread.run(() -> {
                    viewModel.transferStatusProperty().set(
                            i18nService.get("status.transferFailed", t.getMessage()));
                    viewModel.transferProgressProperty().set(0);
                });
            }
        };
    }

    private void executeTransfer(CompletableFuture<Void> future, Runnable onSuccess) {
        future.whenComplete((u, t) -> FxThread.run(() -> {
            if (t != null) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                viewModel.transferStatusProperty().set(
                        i18nService.get("status.transferFailed", cause.getMessage()));
            } else {
                onSuccess.run();
            }
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

    private String formatTime(Instant instant) {
        return instant == null || instant.equals(Instant.EPOCH) ? "" : TIME_FMT.format(instant);
    }

    /** Creates a themed SVG icon Region (same technique as SidebarTreeView). */
    private static Region svgIcon(String pathData, double size) {
        Region r = new Region();
        r.setStyle(String.format(
                "-fx-min-width:%.0fpx;-fx-min-height:%.0fpx;" +
                "-fx-max-width:%.0fpx;-fx-max-height:%.0fpx;" +
                "-fx-pref-width:%.0fpx;-fx-pref-height:%.0fpx;" +
                "-fx-shape:\"%s\";-fx-scale-shape:true;",
                size, size, size, size, size, size, pathData));
        r.getStyleClass().add("action-bar-icon");
        return r;
    }

    private static TreeItem<FileNode> placeholder() {
        return new TreeItem<>(new FileNode("\0", "", true, 0, null));
    }

    // ── SVG icon button (same style as sidebar action bar) ────────────────────

    private Button svgNavButton(String svgPath, String tooltip) {
        Button btn = new Button();
        btn.setGraphic(svgIcon(svgPath, 13));
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        btn.getStyleClass().add("icon-btn");
        return btn;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record FileNode(String name, String path, boolean isDirectory, long size, Instant modifiedAt) {
        @Override public String toString() { return name; }
    }

    private static class DirTreeCell extends TreeCell<FileNode> {
        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.name().equals("\0")) {
                setText(null); setGraphic(null); return;
            }
            Region icon = svgIcon(ICON_FOLDER, 13);
            Label lbl = new Label(item.name());
            HBox box = new HBox(5, icon, lbl);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(box); setText(null);
        }
    }
}
