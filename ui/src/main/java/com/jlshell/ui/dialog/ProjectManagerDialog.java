package com.jlshell.ui.dialog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectUpdatedEvent;
import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import com.jlshell.plugin.api.project.ProjectManagementContext;
import com.jlshell.program.plugin.loader.ProjectIntegrationRegistry;
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
import java.io.File;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import com.jlshell.core.support.MutableValue;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.swing.SwingSettingsFileChooser;
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
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目管理对话框：创建、重命名、删除项目。
 */
public class ProjectManagerDialog {

    private static final Logger log = LoggerFactory.getLogger(ProjectManagerDialog.class);
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

    public static CompletableFuture<Void> show(Object owner, ConnectionProfileService service,
            I18nService i18n, ThemeService themes, VaultService vault, String activeProjectId, Consumer<String> switchProject) {
        return SwingFormDialog.showMessageAsync(i18n.get("project.manage.title"), "jlshell.projects", i18n, themes,
                () -> new ProjectEditor(service, vault, i18n, activeProjectId, switchProject), 840, 500);
    }

    private static final class ProjectEditor extends JPanel implements AutoCloseable {
        private final ConnectionProfileService service;
        private final VaultService vault;
        private final I18nService i18n;
        private final String activeProject;
        private final Consumer<String> switchProject;
        private final DefaultListModel<ProjectProfile> model = new DefaultListModel<>();
        private final JList<ProjectProfile> list = new JList<>(model);
        private final JTextField search = new JTextField(), name = new JTextField();
        private final JTextArea description = new JTextArea(4, 30);
        private final JPanel contributions = new JPanel();
        private final JLabel status = new JLabel(" ");
        private final JButton save, delete, switchButton, export;
        private final List<ContributionState> states = new ArrayList<>();
        private final List<JComponent> contributionViews = new ArrayList<>();
        private List<ProjectProfile> projects = List.of();
        private long revision;
        private boolean closed, busy, updating;

        ProjectEditor(ConnectionProfileService service, VaultService vault, I18nService i18n, String activeProject, Consumer<String> switchProject) {
            super(new BorderLayout(16, 12));
            this.service = service; this.vault = vault; this.i18n = i18n; this.activeProject = activeProject; this.switchProject = switchProject;
            setName("dialog.content"); setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            list.setName("projects.list"); list.setFixedCellHeight(66); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer((items, project, index, selected, focused) -> {
                var row = new JPanel(new GridLayout(2, 1, 0, 6));
                row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                row.setBackground(selected ? items.getSelectionBackground() : items.getBackground());
                var title = new JLabel(project.name() + (Objects.equals(project.id(), activeProject) ? " · " + i18n.get("project.status.active") : ""));
                var detail = new JLabel(project.description());
                for (var label : List.of(title, detail)) label.setForeground(selected ? items.getSelectionForeground() : items.getForeground());
                row.add(title); row.add(detail); return row;
            });
            search.putClientProperty("JTextField.placeholderText", i18n.get("project.search.prompt"));
            var left = new JPanel(new BorderLayout(0, 8)); left.add(search, BorderLayout.NORTH);
            left.add(new JScrollPane(list)); left.setPreferredSize(new Dimension(260, 0)); add(left, BorderLayout.WEST);
            var form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            name.setName("projects.name"); description.setName("projects.description"); description.setLineWrap(true); description.setWrapStyleWord(true);
            var nameLabel = new JLabel(i18n.get("project.field.name"));
            nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            name.setMaximumSize(new Dimension(Integer.MAX_VALUE, name.getPreferredSize().height));
            form.add(nameLabel);
            form.add(Box.createVerticalStrut(6));
            form.add(name);
            form.add(Box.createVerticalStrut(14));
            var descriptionLabel = new JLabel(i18n.get("project.field.description"));
            descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(descriptionLabel);
            form.add(Box.createVerticalStrut(6));
            var descriptionScroll = new JScrollPane(description);
            descriptionScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            descriptionScroll.setPreferredSize(new Dimension(0, 100));
            descriptionScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
            form.add(descriptionScroll);
            contributions.setLayout(new BoxLayout(contributions, BoxLayout.Y_AXIS));
            contributions.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(Box.createVerticalStrut(14));
            form.add(contributions);
            form.add(Box.createVerticalGlue());
            var right = new JPanel(new BorderLayout(0, 12)); right.add(form);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            var create = button("project.action.new", this::newProject);
            save = button("project.action.save", this::save);
            delete = button("project.action.delete", this::delete);
            var importButton = button("project.action.importConfig", this::importConfig);
            export = button("project.action.exportConfig", this::exportConfig);
            switchButton = button("project.action.switchTo", this::switchTo);
            create.setName("projects.new"); save.setName("projects.save"); delete.setName("projects.delete");
            for (var button : List.of(create, save, delete, importButton, export, switchButton)) actions.add(button);
            var footer = new JPanel(new BorderLayout(0, 8)); footer.add(actions); footer.add(status, BorderLayout.SOUTH);
            right.add(footer, BorderLayout.SOUTH); add(right);
            list.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting() && !updating) selectCurrent();
            });
            DocumentListener changed = new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { publishDraft(); }
                public void removeUpdate(DocumentEvent event) { publishDraft(); }
                public void changedUpdate(DocumentEvent event) { publishDraft(); }
            };
            name.getDocument().addDocumentListener(changed); description.getDocument().addDocumentListener(changed);
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { filter(); }
                public void removeUpdate(DocumentEvent event) { filter(); }
                public void changedUpdate(DocumentEvent event) { filter(); }
            });
            rebuildContributions(); updateEnabled(); reload(activeProject);
        }
        private void newProject() {
            if (busy) return;
            updating = true; list.clearSelection(); updating = false;
            name.setText(""); description.setText(""); rebuildContributions(); updateEnabled(); name.requestFocusInWindow();
        }
        private void selectCurrent() {
            ProjectProfile selected = list.getSelectedValue();
            name.setText(selected == null ? "" : selected.name());
            description.setText(selected == null ? "" : Objects.toString(selected.description(), ""));
            rebuildContributions(); updateEnabled();
        }
        private JButton button(String key, Runnable action) {
            var button = new JButton(i18n.get(key)); button.addActionListener(event -> action.run()); return button;
        }
        private void publishDraft() {
            for (ContributionState state : states) { state.name().set(name.getText()); state.description().set(description.getText()); }
        }
        private void rebuildContributions() {
            closeContributions();
            ProjectProfile selected = list.getSelectedValue();
            if (isDefaultProject(selected)) return;
            for (var registered : ProjectIntegrationRegistry.shared().contributions()) {
                var title = new MutableValue<>(name.getText());
                var detail = new MutableValue<>(description.getText());
                var creation = selected == null ? new ProjectCreationContext(title, detail) : null;
                var management = selected == null ? null : new ProjectManagementContext(selected.id(), title, detail);
                try {
                    var factory = creation != null ? registered.contribution().createView(creation) : registered.contribution().createManagementView(management);
                    if (factory == null) continue;
                    var view = Objects.requireNonNull(factory.get());
                    contributions.add(view); contributionViews.add(view);
                    states.add(new ContributionState(registered.contribution(), creation, management, title, detail));
                } catch (RuntimeException | LinkageError error) { log.warn("Cannot build project contribution {}/{}", registered.pluginId(), registered.contribution().id(), error); }
            }
            contributions.revalidate(); contributions.repaint();
        }
        private void closeContributions() {
            states.clear();
            for (var view : contributionViews) if (view instanceof AutoCloseable resource) try { resource.close(); }
            catch (Exception error) { log.warn("Cannot close project contribution", error); }
            contributionViews.clear(); contributions.removeAll();
        }
        private void reload(String selectedId) {
            long request = ++revision;
            busy = true; updateEnabled();
            CompletableFuture.supplyAsync(service::listProjects).whenComplete((values, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || request != revision) return;
                busy = false; updateEnabled();
                if (error != null) { fail(error); return; }
                var all = new ArrayList<ProjectProfile>();
                all.add(new ProjectProfile(null, i18n.get("project.label.default"), i18n.get("project.default.description"))); all.addAll(values);
                projects = List.copyOf(all); filter();
                projects.stream().filter(project -> Objects.equals(project.id(), selectedId)).findFirst().ifPresent(project -> list.setSelectedValue(project, true));
            }));
        }
        private void filter() {
            ProjectProfile selected = list.getSelectedValue();
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            updating = true; model.clear();
            model.addAll(projects.stream().filter(project -> ProjectManagerDialog.contains(project.name(), query) || ProjectManagerDialog.contains(project.description(), query)).toList());
            if (selected != null) list.setSelectedValue(selected, true);
            updating = false;
            if (!Objects.equals(selected, list.getSelectedValue())) selectCurrent();
        }
        private void updateEnabled() {
            ProjectProfile selected = list.getSelectedValue();
            boolean editable = !busy && !isDefaultProject(selected);
            name.setEnabled(editable); description.setEnabled(editable); save.setEnabled(editable);
            delete.setEnabled(!busy && selected != null && !isDefaultProject(selected));
            switchButton.setEnabled(!busy && selected != null && !Objects.equals(selected.id(), activeProject));
            export.setEnabled(!busy && selected != null); list.setEnabled(!busy);
        }
        private void save() {
            if (busy || name.getText().isBlank() || isDefaultProject(list.getSelectedValue())) return;
            String validation = validateProjectContributions(states);
            if (validation != null) { status.setText(validation); return; }
            ProjectProfile selected = list.getSelectedValue();
            String id = selected == null ? null : selected.id(), title = name.getText().strip(), detail = description.getText().strip();
            var submitted = List.copyOf(states);
            busy = true; updateEnabled();
            CompletableFuture.supplyAsync(() -> service.saveProject(id, title, detail)).whenComplete((saved, error) -> SwingUtilities.invokeLater(() -> {
                busy = false;
                if (error == null) {
                    if (selected == null) notifyProjectContributions(submitted, new ProjectCreatedEvent(saved.id(), saved.name(), saved.description(), Instant.now()));
                    else notifyProjectManagementContributions(submitted, new ProjectUpdatedEvent(saved.id(), saved.name(), saved.description(), Instant.now()));
                }
                if (closed) return;
                updateEnabled(); if (error != null) fail(error); else reload(saved.id());
            }));
        }
        private void delete() {
            ProjectProfile selected = list.getSelectedValue();
            if (busy || selected == null || isDefaultProject(selected)) return;
            confirm(i18n.get("project.action.deleteConfirm", selected.name()), () -> run(() -> { service.deleteProject(selected.id()); return true; }, ignored -> {
                list.clearSelection(); name.setText(""); description.setText(""); rebuildContributions(); reload(activeProject);
            }));
        }
        private void switchTo() {
            ProjectProfile selected = list.getSelectedValue();
            if (busy || selected == null || Objects.equals(selected.id(), activeProject)) return;
            confirm(i18n.get("project.switch.detail", selected.name()), () -> {
                var window = SwingUtilities.getWindowAncestor(this); if (window != null) window.dispose();
                if (switchProject != null) switchProject.accept(selected.id());
            });
        }
        private void confirm(String message, Runnable accepted) {
            SwingFormDialog.showAsync(i18n.get("project.manage.title"), "jlshell.projects.confirm", i18n,
                    () -> new SwingFormDialog.Form<>(new JLabel(message), () -> Boolean.TRUE), 520, 220)
                    .thenAccept(value -> { if (!closed && Boolean.TRUE.equals(value)) accepted.run(); });
        }
        private void importConfig() {
            if (busy) return;
            SwingSettingsFileChooser.openAsync(i18n.get("project.import.title"), false, "json").thenAccept(file -> {
                if (file == null || closed) return;
                run(() -> projectPackageRequiresCode(file), required -> {
                    if (!required) { importFile(file, ""); return; }
                    codeDialog(i18n).thenAccept(code -> { if (code != null && !closed) importFile(file, code); });
                });
            });
        }
        private void importFile(File file, String code) {
            run(() -> importProjectConfig(file, service, vault, code), project -> reload(project.id()));
        }
        private void exportConfig() {
            ProjectProfile selected = list.getSelectedValue();
            if (busy || selected == null) return;
            SwingSettingsFileChooser.saveAsync(i18n.get("project.export.title"), safeFileName(selected.name()) + ".jlshell-project.json", "json")
                    .thenAccept(file -> {
                        if (file == null || closed) return;
                        String code = generateProjectPackageCode();
                        busy = true; updateEnabled();
                        CompletableFuture.runAsync(() -> {
                            try { exportProjectConfig(file, selected, service, vault, code); }
                            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                        }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                            busy = false;
                            if (!closed) updateEnabled();
                            if (error == null) showCode(i18n, code);
                            else if (!closed) fail(error);
                            else SwingFormDialog.showMessageAsync(i18n.get("project.export.title"), "jlshell.projects.exportError", i18n,
                                    () -> new JLabel(Objects.toString(error.getCause() == null ? error.getMessage() : error.getCause().getMessage())), 560, 220);
                        }));
                    });
        }
        @FunctionalInterface private interface Operation<T> { T run() throws Exception; }
        private <T> void run(Operation<T> operation, Consumer<T> completed) {
            if (closed || busy) return;
            busy = true; updateEnabled();
            CompletableFuture.supplyAsync(() -> {
                try { return operation.run(); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }).whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
                busy = false; if (closed) return;
                updateEnabled(); if (error != null) fail(error); else completed.accept(value);
            }));
        }
        private void fail(Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            status.setText(Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        }
        @Override public void close() { closed = true; revision++; closeContributions(); }
    }

    private record ContributionState(ProjectCreationContribution contribution, ProjectCreationContext creationContext,
            ProjectManagementContext managementContext, MutableValue<String> name,
            MutableValue<String> description) {}

    private static String validateProjectContributions(List<ContributionState> states) {
        for (ContributionState state : states) {
            try {
                String error = state.creationContext() != null
                        ? state.contribution().validate(state.creationContext()).orElse(null)
                        : state.contribution().validateManagement(state.managementContext()).orElse(null);
                if (error != null && !error.isBlank()) {
                    return error;
                }
            } catch (RuntimeException error) {
                return "Plugin project validation failed: " + error.getMessage();
            }
        }
        return null;
    }

    private static void notifyProjectContributions(List<ContributionState> states,
                                                   ProjectCreatedEvent event) {
        for (ContributionState state : List.copyOf(states)) {
            try {
                state.contribution().onProjectCreated(event, state.creationContext());
            } catch (RuntimeException error) {
                log.warn("Project contribution callback failed: {}",
                        state.contribution().id(), error);
            }
        }
    }

    private static void notifyProjectManagementContributions(
            List<ContributionState> states, ProjectUpdatedEvent event) {
        for (ContributionState state : List.copyOf(states)) {
            try {
                state.contribution().onProjectUpdated(event, state.managementContext());
            } catch (RuntimeException error) {
                log.warn("Project management contribution callback failed: {}",
                        state.contribution().id(), error);
            }
        }
    }

    private static boolean contains(String value, String query) { return value != null && value.toLowerCase(Locale.ROOT).contains(query); }
    private static boolean isDefaultProject(ProjectProfile project) { return project != null && project.id() == null; }

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

    private static CompletableFuture<String> codeDialog(I18nService i18n) {
        return SwingFormDialog.showAsync(i18n.get("project.import.codeTitle"), "jlshell.projects.code", i18n, () -> {
            var field = new JPasswordField();
            var body = new JPanel(new BorderLayout(0, 8));
            body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            body.add(new JLabel(i18n.get("project.import.codePrompt")), BorderLayout.NORTH); body.add(field);
            return new SwingFormDialog.Form<>(body, () -> {
                char[] value = field.getPassword();
                try { if (value.length == 0) return null; String code = new String(value); field.setText(""); return code; }
                finally { java.util.Arrays.fill(value, '\0'); }
            });
        }, 520, 240);
    }
    private static void showCode(I18nService i18n, String code) {
        SwingFormDialog.showMessageAsync(i18n.get("project.export.codeTitle"), "jlshell.projects.exportCode", i18n, () -> {
            var body = new JPanel(new BorderLayout(0, 12));
            body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            body.add(new JLabel(i18n.get("project.export.codeHint")), BorderLayout.NORTH);
            var field = new JTextField(code); field.setEditable(false); body.add(field); return body;
        }, 620, 240);
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "project" : value.trim();
        return name.replaceAll("[\\\\/:*?\"<>|]+", "_");
    }

}
