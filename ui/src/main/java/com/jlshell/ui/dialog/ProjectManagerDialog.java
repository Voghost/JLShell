package com.jlshell.ui.dialog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.VaultEntryFormData;
import com.jlshell.ui.model.VaultEntryProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.theme.ThemeService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * 项目管理对话框：创建、重命名、删除项目。
 */
public class ProjectManagerDialog {

    private static final Gson GSON = new Gson();
    private static final String PROJECT_CONFIG_TYPE = "JLSHELL_PROJECT_CONFIG_V1";
    private static final int PROJECT_PACKAGE_ITERATIONS = 240_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> DEFAULT_PROJECT_NAMES = Set.of("默认", "Default");

    private ProjectManagerDialog() {}

    public static void show(Stage owner, ConnectionProfileService service, I18nService i18n,
                            ThemeService themeService, VaultService vaultService,
                            String activeProjectId, Consumer<String> onSwitchProject) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("project.manage.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().setPrefHeight(460);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ObservableList<ProjectProfile> items = FXCollections.observableArrayList();
        refreshProjectItems(items, service, i18n);
        FilteredList<ProjectProfile> filteredItems = new FilteredList<>(items, item -> true);
        ListView<ProjectProfile> listView = new ListView<>(filteredItems);
        listView.getStyleClass().add("project-manager-list");
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Label nameLbl = new Label();
            private final Label descLbl = new Label();
            private final Label activeBadge = new Label();
            private final Region spacer = new Region();
            private final HBox titleRow = new HBox(8, nameLbl, spacer, activeBadge);
            private final VBox box = new VBox(4, titleRow, descLbl);

            {
                nameLbl.getStyleClass().add("project-manager-item-name");
                descLbl.getStyleClass().add("project-manager-item-description");
                activeBadge.getStyleClass().add("project-active-badge");
                activeBadge.setVisible(false);
                activeBadge.setManaged(false);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                nameLbl.setMaxWidth(Double.MAX_VALUE);
                descLbl.setMaxWidth(Double.MAX_VALUE);
                nameLbl.setTextOverrun(OverrunStyle.ELLIPSIS);
                descLbl.setTextOverrun(OverrunStyle.ELLIPSIS);
                nameLbl.setWrapText(false);
                descLbl.setWrapText(false);
                titleRow.setAlignment(Pos.CENTER_LEFT);
                box.getStyleClass().add("project-manager-item");
                box.setMaxWidth(Double.MAX_VALUE);
            }

            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                nameLbl.setText(item.name() == null ? "" : item.name());
                String description = isDefaultProject(item)
                        ? i18n.get("project.default.description")
                        : item.description() == null || item.description().isBlank()
                        ? i18n.get("project.field.description")
                        : item.description();
                descLbl.setText(description);
                boolean isActive = Objects.equals(item.id(), activeProjectId);
                box.getStyleClass().remove("project-manager-item-active");
                if (isActive) {
                    activeBadge.setText(i18n.get("project.status.active"));
                    activeBadge.setVisible(true);
                    activeBadge.setManaged(true);
                    box.getStyleClass().add("project-manager-item-active");
                } else {
                    activeBadge.setVisible(false);
                    activeBadge.setManaged(false);
                }
                setGraphic(box);
                setText(null);
            }
        });
        listView.setPrefWidth(280);
        listView.setMinWidth(240);
        listView.setMaxWidth(280);

        TextField searchField = new TextField();
        searchField.getStyleClass().add("project-manager-search");
        searchField.setPromptText(i18n.get("project.search.prompt"));
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = newValue == null ? "" : newValue.trim().toLowerCase();
            filteredItems.setPredicate(item -> {
                if (query.isBlank()) {
                    return true;
                }
                return contains(item.name(), query) || contains(item.description(), query);
            });
        });

        TextField nameField = new TextField();
        nameField.getStyleClass().add("project-manager-input");
        nameField.setPromptText(i18n.get("project.field.name"));
        TextArea descField = new TextArea();
        descField.getStyleClass().add("project-manager-input");
        descField.setPromptText(i18n.get("project.field.description"));
        descField.setPrefRowCount(5);
        descField.setWrapText(true);

        listView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                nameField.setText(nv.name());
                descField.setText(nv.description() != null ? nv.description() : "");
                boolean defaultProject = isDefaultProject(nv);
                nameField.setDisable(defaultProject);
                descField.setDisable(defaultProject);
            }
        });

        Button saveBtn = new Button(i18n.get("project.action.save"));
        Button newBtn = new Button(i18n.get("project.action.new"));
        Button deleteBtn = new Button(i18n.get("project.action.delete"));
        Button switchBtn = new Button(i18n.get("project.action.switchTo"));
        switchBtn.getStyleClass().add("button-primary");
        switchBtn.setDisable(true);

        switchBtn.setOnAction(e -> {
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null || Objects.equals(selected.id(), activeProjectId)) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("project.switch.detail", selected.name()),
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(i18n.get("project.switch.confirm", selected.name()));
            themeService.applyToDialog(confirm);
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused -> {
                if (onSwitchProject != null) onSwitchProject.accept(selected.id());
                dialog.close();
            });
        });

        listView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean noSelection = nv == null;
            boolean defaultProject = nv != null && isDefaultProject(nv);
            switchBtn.setDisable(noSelection || Objects.equals(nv.id(), activeProjectId));
            saveBtn.setDisable(defaultProject);
            deleteBtn.setDisable(noSelection || defaultProject);
        });

        newBtn.setOnAction(e -> {
            listView.getSelectionModel().clearSelection();
            nameField.setDisable(false);
            descField.setDisable(false);
            nameField.clear();
            descField.clear();
            nameField.requestFocus();
        });

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) return;
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && isDefaultProject(selected)) return;
            String id = selected != null ? selected.id() : null;
            ProjectProfile saved = service.saveProject(id, name, descField.getText().trim());
            refreshProjectItems(items, service, i18n);
            items.stream().filter(p -> Objects.equals(p.id(), saved.id()))
                    .findFirst().ifPresent(listView.getSelectionModel()::select);
        });

        deleteBtn.setOnAction(e -> {
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null || isDefaultProject(selected)) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("project.action.deleteConfirm").replace("{0}", selected.name()),
                    ButtonType.YES, ButtonType.NO);
            themeService.applyToDialog(confirm);
            confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
                service.deleteProject(selected.id());
                refreshProjectItems(items, service, i18n);
                nameField.clear();
                descField.clear();
            });
        });

        Button importBtn = new Button(i18n.get("project.action.importConfig"));
        Button exportBtn = new Button(i18n.get("project.action.exportConfig"));

        importBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(i18n.get("project.import.title"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    i18n.get("project.import.filter"), "*.json"));
            File file = chooser.showOpenDialog(owner != null ? owner : dialog.getDialogPane().getScene().getWindow());
            if (file == null) {
                return;
            }
            try {
                String code = projectPackageRequiresCode(file)
                        ? showProjectPackageCodeDialog(dialog, i18n, themeService)
                        : "";
                if (code == null) {
                    return;
                }
                ProjectProfile imported = importProjectConfig(file, service, vaultService, code);
                refreshProjectItems(items, service, i18n);
                items.stream().filter(p -> Objects.equals(p.id(), imported.id()))
                        .findFirst().ifPresent(listView.getSelectionModel()::select);
            } catch (Exception ex) {
                showError(i18n, themeService, dialog, i18n.get("project.import.failed", ex.getMessage()));
            }
        });

        exportBtn.setOnAction(e -> {
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle(i18n.get("project.export.title"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    i18n.get("project.import.filter"), "*.json"));
            chooser.setInitialFileName(safeFileName(selected.name()) + ".jlshell-project.json");
            File file = chooser.showSaveDialog(owner != null ? owner : dialog.getDialogPane().getScene().getWindow());
            if (file == null) {
                return;
            }
            try {
                String code = generateProjectPackageCode();
                exportProjectConfig(file, selected, service, vaultService, code);
                showProjectPackageCodeResult(dialog, i18n, themeService, code);
            } catch (Exception ex) {
                showError(i18n, themeService, dialog, i18n.get("project.export.failed", ex.getMessage()));
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                exportBtn.setDisable(nv == null));
        exportBtn.setDisable(true);

        Label nameLabel = new Label(i18n.get("project.field.name"));
        nameLabel.getStyleClass().add("project-manager-field-label");
        Label descLabel = new Label(i18n.get("project.field.description"));
        descLabel.getStyleClass().add("project-manager-field-label");
        VBox form = new VBox(8, nameLabel, nameField, descLabel, descField);
        form.getStyleClass().add("project-manager-form");
        VBox.setVgrow(descField, Priority.ALWAYS);

        HBox editButtons = new HBox(8, newBtn, saveBtn, deleteBtn, importBtn, exportBtn);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, editButtons, spacer, switchBtn);
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        VBox rightPane = new VBox(12, form, bottomBar);
        HBox.setHgrow(rightPane, Priority.ALWAYS);
        VBox.setVgrow(form, Priority.ALWAYS);

        VBox leftPane = new VBox(8, searchField, listView);
        VBox.setVgrow(listView, Priority.ALWAYS);
        leftPane.setPrefWidth(280);
        leftPane.setMinWidth(240);
        leftPane.setMaxWidth(280);

        HBox content = new HBox(14, leftPane, rightPane);
        content.getStyleClass().add("project-manager-content");
        content.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static void refreshProjectItems(ObservableList<ProjectProfile> items,
                                            ConnectionProfileService service,
                                            I18nService i18n) {
        items.setAll(new ProjectProfile(null, i18n.get("project.label.default"), i18n.get("project.default.description")));
        items.addAll(service.listProjects());
    }

    private static boolean isDefaultProject(ProjectProfile project) {
        return project != null && project.id() == null;
    }

    private static void exportProjectConfig(File file, ProjectProfile project, ConnectionProfileService service,
                                            VaultService vaultService, String packageCode) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("type", PROJECT_CONFIG_TYPE);
        root.addProperty("version", 1);
        JsonObject data = new JsonObject();
        data.addProperty("name", project.name());
        data.addProperty("description", project.description() == null ? "" : project.description());
        data.addProperty("defaultProject", isDefaultProject(project));
        root.add("project", data);

        JsonArray folders = new JsonArray();
        for (FolderProfile folder : service.listFolders(project.id())) {
            JsonObject folderJson = new JsonObject();
            folderJson.addProperty("id", folder.id());
            folderJson.addProperty("name", folder.name());
            folderJson.addProperty("parentId", folder.parentId());
            folderJson.addProperty("sortOrder", folder.sortOrder());
            folders.add(folderJson);
        }
        root.add("folders", folders);

        JsonArray connections = new JsonArray();
        for (ConnectionProfile connection : service.listProfilesByProject(project.id())) {
            JsonObject connectionJson = new JsonObject();
            connectionJson.addProperty("displayName", connection.displayName());
            connectionJson.addProperty("host", connection.host());
            connectionJson.addProperty("port", connection.port());
            connectionJson.addProperty("username", connection.username());
            connectionJson.addProperty("authenticationType", enumName(connection.authenticationType()));
            connectionJson.addProperty("hostKeyVerificationMode", enumName(connection.hostKeyVerificationMode()));
            connectionJson.addProperty("description", connection.description());
            connectionJson.addProperty("defaultRemotePath", connection.defaultRemotePath());
            connectionJson.addProperty("favorite", connection.favorite());
            connectionJson.addProperty("connectionType", enumName(connection.connectionType()));
            connectionJson.addProperty("folderId", connection.folderId());
            connectionJson.addProperty("vaultEntryId", connection.vaultEntryId());
            connections.add(connectionJson);
        }
        root.add("connections", connections);

        JsonArray vaultEntries = new JsonArray();
        for (VaultEntryProfile vault : vaultService.listByProject(project.id())) {
            VaultEntryFormData form = vaultService.loadForm(vault.id());
            JsonObject vaultJson = new JsonObject();
            vaultJson.addProperty("id", form.id());
            vaultJson.addProperty("name", form.name());
            vaultJson.addProperty("authenticationType", enumName(form.authenticationType()));
            vaultJson.addProperty("encryptionMode", enumName(form.encryptionMode()));
            vaultJson.addProperty("password", form.password());
            vaultJson.addProperty("passphrase", form.passphrase());
            vaultJson.addProperty("keyContent", form.keyContent());
            vaultJson.addProperty("privateKeyPath", form.privateKeyPath());
            vaultEntries.add(vaultJson);
        }
        if (!vaultEntries.isEmpty()) {
            root.add("vault", encryptVaultEntries(vaultEntries, packageCode));
        }
        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static ProjectProfile importProjectConfig(File file, ConnectionProfileService service,
                                                      VaultService vaultService, String packageCode) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!PROJECT_CONFIG_TYPE.equals(root.has("type") ? root.get("type").getAsString() : "")) {
            throw new IllegalArgumentException("Unsupported project config");
        }
        JsonObject project = root.getAsJsonObject("project");
        if (project == null) {
            throw new IllegalArgumentException("Missing project section");
        }
        String name = project.has("name") && !project.get("name").isJsonNull()
                ? project.get("name").getAsString().trim()
                : "";
        if (name.isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }
        String description = project.has("description") && !project.get("description").isJsonNull()
                ? project.get("description").getAsString()
                : "";
        boolean defaultProjectPackage = booleanOrDefault(project, "defaultProject", false)
                || DEFAULT_PROJECT_NAMES.contains(name);
        ProjectProfile importedProject = defaultProjectPackage
                ? new ProjectProfile(null, name, description)
                : service.saveProject(null, uniqueProjectName(name, service), description);
        Map<String, String> folderIdMap = importFolders(root, service, importedProject.id());
        Map<String, String> vaultIdMap = importVaultEntries(root, vaultService, importedProject.id(), packageCode);
        importConnections(root, service, importedProject.id(), folderIdMap, vaultIdMap);
        return importedProject;
    }

    private static Map<String, String> importFolders(JsonObject root, ConnectionProfileService service, String projectId) {
        Map<String, String> idMap = new HashMap<>();
        if (!root.has("folders") || !root.get("folders").isJsonArray()) {
            return idMap;
        }
        JsonArray folders = root.getAsJsonArray("folders");
        Set<Integer> importedIndexes = new HashSet<>();
        int guard = 0;
        while (importedIndexes.size() < folders.size() && guard++ < folders.size() * 4) {
            for (int i = 0; i < folders.size(); i++) {
                if (importedIndexes.contains(i) || !folders.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject folder = folders.get(i).getAsJsonObject();
                String oldId = stringOrNull(folder, "id");
                String oldParentId = stringOrNull(folder, "parentId");
                if (oldParentId != null && !idMap.containsKey(oldParentId)) {
                    continue;
                }
                String name = stringOrNull(folder, "name");
                if (name == null || name.isBlank()) {
                    name = "Folder";
                }
                String parentId = oldParentId == null ? null : idMap.get(oldParentId);
                FolderProfile saved = findExistingFolder(service, projectId, parentId, name);
                if (saved == null) {
                    saved = service.saveFolder(null, name, parentId, projectId);
                }
                if (oldId != null) {
                    idMap.put(oldId, saved.id());
                }
                importedIndexes.add(i);
            }
        }
        return idMap;
    }

    private static void importConnections(JsonObject root, ConnectionProfileService service,
                                          String projectId, Map<String, String> folderIdMap,
                                          Map<String, String> vaultIdMap) {
        if (!root.has("connections") || !root.get("connections").isJsonArray()) {
            return;
        }
        JsonArray connections = root.getAsJsonArray("connections");
        List<String> existingNames = service.listProfilesByProject(projectId).stream()
                .map(ConnectionProfile::displayName)
                .filter(Objects::nonNull)
                .toList();
        Set<String> names = new HashSet<>(existingNames);
        Set<String> existingKeys = service.listProfilesByProject(projectId).stream()
                .map(ProjectManagerDialog::connectionIdentity)
                .collect(Collectors.toSet());
        for (int i = 0; i < connections.size(); i++) {
            if (!connections.get(i).isJsonObject()) {
                continue;
            }
            JsonObject c = connections.get(i).getAsJsonObject();
            ConnectionType type = enumValue(ConnectionType.class, stringOrNull(c, "connectionType"), ConnectionType.SSH);
            String oldFolderId = stringOrNull(c, "folderId");
            String oldVaultEntryId = stringOrNull(c, "vaultEntryId");
            String folderId = oldFolderId == null ? null : folderIdMap.get(oldFolderId);
            String rawDisplayName = stringOrNull(c, "displayName");
            String host = type == ConnectionType.LOCAL_SHELL ? "localhost" : defaultString(stringOrNull(c, "host"), "localhost");
            int port = type == ConnectionType.LOCAL_SHELL ? 0 : intOrDefault(c, "port", 22);
            String username = type == ConnectionType.LOCAL_SHELL ? defaultString(stringOrNull(c, "username"), System.getProperty("user.name", "local"))
                    : defaultString(stringOrNull(c, "username"), "user");
            String identity = connectionIdentity(type, rawDisplayName, host, port, username, folderId);
            if (existingKeys.contains(identity)) {
                continue;
            }
            String displayName = uniqueName(stringOrNull(c, "displayName"), names, "Connection");
            names.add(displayName);
            ConnectionFormData form = new ConnectionFormData(
                    null,
                    displayName,
                    host,
                    port,
                    username,
                    enumValue(AuthenticationType.class, stringOrNull(c, "authenticationType"), AuthenticationType.PASSWORD),
                    "",
                    "",
                    "",
                    enumValue(HostKeyVerificationMode.class, stringOrNull(c, "hostKeyVerificationMode"), HostKeyVerificationMode.STRICT),
                    stringOrNull(c, "description"),
                    stringOrNull(c, "defaultRemotePath"),
                    booleanOrDefault(c, "favorite", false),
                    projectId,
                    type,
                    folderId,
                    oldVaultEntryId == null ? null : vaultIdMap.get(oldVaultEntryId),
                    null
            );
            service.saveImported(form);
            existingKeys.add(connectionIdentity(type, displayName, host, port, username, folderId));
        }
    }

    private static FolderProfile findExistingFolder(ConnectionProfileService service, String projectId,
                                                    String parentId, String name) {
        String normalizedName = normalizeIdentityPart(name);
        return service.listFolders(projectId).stream()
                .filter(folder -> Objects.equals(folder.parentId(), parentId))
                .filter(folder -> Objects.equals(normalizeIdentityPart(folder.name()), normalizedName))
                .findFirst()
                .orElse(null);
    }

    private static String connectionIdentity(ConnectionProfile profile) {
        return connectionIdentity(
                profile.connectionType() == null ? ConnectionType.SSH : profile.connectionType(),
                profile.displayName(),
                profile.connectionType() == ConnectionType.LOCAL_SHELL ? "localhost" : profile.host(),
                profile.connectionType() == ConnectionType.LOCAL_SHELL ? 0 : profile.port(),
                profile.connectionType() == ConnectionType.LOCAL_SHELL
                        ? defaultString(profile.username(), System.getProperty("user.name", "local"))
                        : profile.username(),
                profile.folderId()
        );
    }

    private static String connectionIdentity(ConnectionType type, String displayName, String host,
                                             int port, String username, String folderId) {
        return String.join("|",
                type == null ? ConnectionType.SSH.name() : type.name(),
                normalizeIdentityPart(displayName),
                normalizeIdentityPart(host),
                String.valueOf(port),
                normalizeIdentityPart(username),
                folderId == null ? "" : folderId
        );
    }

    private static String normalizeIdentityPart(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> importVaultEntries(JsonObject root, VaultService vaultService,
                                                          String projectId, String packageCode) throws Exception {
        Map<String, String> idMap = new HashMap<>();
        if (!root.has("vault") || !root.get("vault").isJsonObject()) {
            return idMap;
        }
        JsonArray vaultEntries = decryptVaultEntries(root.getAsJsonObject("vault"), packageCode);
        Set<String> names = new HashSet<>();
        vaultService.listByProject(projectId).forEach(v -> {
            if (v.name() != null) {
                names.add(v.name().trim());
            }
        });
        for (int i = 0; i < vaultEntries.size(); i++) {
            if (!vaultEntries.get(i).isJsonObject()) {
                continue;
            }
            JsonObject v = vaultEntries.get(i).getAsJsonObject();
            String oldId = stringOrNull(v, "id");
            String name = uniqueName(stringOrNull(v, "name"), names, "Vault");
            names.add(name);
            VaultEntryProfile saved = vaultService.save(new VaultEntryFormData(
                    null,
                    name,
                    enumValue(AuthenticationType.class, stringOrNull(v, "authenticationType"), AuthenticationType.PASSWORD),
                    enumValue(VaultEncryptionMode.class, stringOrNull(v, "encryptionMode"), VaultEncryptionMode.SYSTEM),
                    defaultString(stringOrNull(v, "password"), ""),
                    defaultString(stringOrNull(v, "passphrase"), ""),
                    defaultString(stringOrNull(v, "keyContent"), ""),
                    defaultString(stringOrNull(v, "privateKeyPath"), ""),
                    projectId
            ));
            if (oldId != null) {
                idMap.put(oldId, saved.id());
            }
        }
        return idMap;
    }

    private static String uniqueProjectName(String requestedName, ConnectionProfileService service) {
        Set<String> existingNames = new HashSet<>();
        service.listProjects().forEach(project -> {
            if (project.name() != null) {
                existingNames.add(project.name().trim());
            }
        });
        return uniqueName(requestedName, existingNames, "Project");
    }

    private static String uniqueName(String requestedName, Set<String> existingNames, String fallback) {
        String baseName = requestedName == null || requestedName.isBlank() ? fallback : requestedName.trim();
        if (!existingNames.contains(baseName)) {
            return baseName;
        }
        int index = 1;
        while (existingNames.contains(baseName + " " + index)) {
            index++;
        }
        return baseName + " " + index;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String stringOrNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intOrDefault(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean booleanOrDefault(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static JsonObject encryptVaultEntries(JsonArray vaultEntries, String packageCode) throws Exception {
        validateProjectPackageCode(packageCode);
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        SecretKeySpec key = deriveProjectPackageKey(packageCode.toCharArray(), salt, PROJECT_PACKAGE_ITERATIONS);
        byte[] encrypted = encryptBytes(GSON.toJson(vaultEntries).getBytes(StandardCharsets.UTF_8), key, iv);
        JsonObject vault = new JsonObject();
        vault.addProperty("kdf", "PBKDF2WithHmacSHA256");
        vault.addProperty("iterations", PROJECT_PACKAGE_ITERATIONS);
        vault.addProperty("cipher", "AES-256-GCM");
        vault.addProperty("salt", encode(salt));
        vault.addProperty("iv", encode(iv));
        vault.addProperty("encrypted", encode(encrypted));
        return vault;
    }

    private static JsonArray decryptVaultEntries(JsonObject vault, String packageCode) throws Exception {
        validateProjectPackageCode(packageCode);
        if (!"PBKDF2WithHmacSHA256".equals(stringOrNull(vault, "kdf"))
                || !"AES-256-GCM".equals(stringOrNull(vault, "cipher"))) {
            throw new IllegalArgumentException("Unsupported vault encryption settings");
        }
        int iterations = intOrDefault(vault, "iterations", PROJECT_PACKAGE_ITERATIONS);
        byte[] salt = decode(stringOrNull(vault, "salt"));
        byte[] iv = decode(stringOrNull(vault, "iv"));
        byte[] encrypted = decode(stringOrNull(vault, "encrypted"));
        SecretKeySpec key = deriveProjectPackageKey(packageCode.toCharArray(), salt, iterations);
        return JsonParser.parseString(new String(decryptBytes(encrypted, key, iv), StandardCharsets.UTF_8)).getAsJsonArray();
    }

    private static boolean projectPackageRequiresCode(File file) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        return root.has("vault") && root.get("vault").isJsonObject();
    }

    private static String generateProjectPackageCode() {
        return encode(randomBytes(24));
    }

    private static void validateProjectPackageCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Project package code is required");
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static SecretKeySpec deriveProjectPackageKey(char[] code, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(code, salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] encryptBytes(byte[] plaintext, SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    private static byte[] decryptBytes(byte[] encrypted, SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Encoded value is required");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    private static void showProjectPackageCodeResult(Dialog<?> owner, I18nService i18n,
                                                     ThemeService themeService, String code) {
        TextArea codeArea = new TextArea(code);
        codeArea.setEditable(false);
        codeArea.setWrapText(true);
        codeArea.setPrefColumnCount(36);
        codeArea.setPrefRowCount(2);
        VBox content = new VBox(8, new Label(i18n.get("project.export.codeHint")), codeArea);
        content.setPadding(new Insets(8, 0, 0, 0));

        Alert alert = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.OK);
        alert.setTitle(i18n.get("project.export.codeTitle"));
        alert.setHeaderText(i18n.get("project.export.codeHeader"));
        alert.getDialogPane().setContent(content);
        themeService.applyToDialog(alert);
        if (owner.getDialogPane().getScene() != null) {
            alert.initOwner(owner.getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }

    private static String showProjectPackageCodeDialog(Dialog<?> owner, I18nService i18n,
                                                       ThemeService themeService) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("project.import.codeTitle"));
        dialog.setHeaderText(i18n.get("project.import.codeHeader"));
        themeService.applyToDialog(dialog);
        if (owner.getDialogPane().getScene() != null) {
            dialog.initOwner(owner.getDialogPane().getScene().getWindow());
        }
        PasswordField field = new PasswordField();
        field.setPromptText(i18n.get("project.import.codePrompt"));
        field.setPrefColumnCount(32);
        VBox content = new VBox(8, new Label(i18n.get("project.import.codePrompt")), field);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        ButtonType importType = new ButtonType(i18n.get("project.action.importConfig"), ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == importType ? field.getText() : null);
        return dialog.showAndWait().orElse(null);
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "project" : value.trim();
        return name.replaceAll("[\\\\/:*?\"<>|]+", "_");
    }

    private static void showError(I18nService i18n, ThemeService themeService, Dialog<?> owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(i18n.get("error.title"));
        themeService.applyToDialog(alert);
        if (owner.getDialogPane().getScene() != null) {
            alert.initOwner(owner.getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }
}
