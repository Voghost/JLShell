package com.jlshell.ui.view;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.VaultEntryProfile;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultKeyService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 连接编辑对话框。
 */
public final class ConnectionDialog {

    private ConnectionDialog() {}

    public static Optional<ConnectionFormData> show(
            Window owner, I18nService i18n, ThemeService themeService, ConnectionFormData initialData,
            List<ProjectProfile> projects, List<FolderProfile> folders,
            VaultService vaultService,
            Function<ConnectionRequest, CompletableFuture<String>> testConnection,
            int connectTimeoutSeconds) {
        Dialog<ConnectionFormData> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(i18n.get(initialData.id() == null ? "dialog.connection.create" : "dialog.connection.edit"));
        themeService.applyToDialog(dialog);

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Connection type
        ComboBox<ConnectionType> typeBox = new ComboBox<>(FXCollections.observableArrayList(ConnectionType.values()));
        typeBox.setValue(initialData.connectionType());

        TextField displayNameField = new TextField(initialData.displayName());
        TextField hostField = new TextField(initialData.host());
        TextField portField = new TextField(String.valueOf(initialData.port()));
        TextField usernameField = new TextField(initialData.username());
        ComboBox<AuthenticationType> authTypeBox = new ComboBox<>(FXCollections.observableArrayList(AuthenticationType.values()));
        authTypeBox.setValue(initialData.authenticationType());
        PasswordField passwordField = new PasswordField();
        passwordField.setText(initialData.password());
        TextField privateKeyPathField = new TextField(initialData.privateKeyPath());
        PasswordField passphraseField = new PasswordField();
        passphraseField.setText(initialData.passphrase());
        ComboBox<HostKeyVerificationMode> hostKeyBox =
                new ComboBox<>(FXCollections.observableArrayList(HostKeyVerificationMode.values()));
        hostKeyBox.setValue(initialData.hostKeyVerificationMode());
        TextField defaultRemotePathField = new TextField(initialData.defaultRemotePath());
        TextArea descriptionArea = new TextArea(initialData.description());
        descriptionArea.setPrefRowCount(3);
        CheckBox favoriteBox = new CheckBox(i18n.get("dialog.connection.favorite"));
        favoriteBox.setSelected(initialData.favorite());

        // Project selector
        ObservableList<ProjectProfile> projectItems = FXCollections.observableArrayList();
        projectItems.add(new ProjectProfile(null, i18n.get("project.label.default"), null));
        projectItems.addAll(projects);
        ComboBox<ProjectProfile> projectBox = new ComboBox<>(projectItems);
        projectBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        projectBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        projectItems.stream()
                .filter(p -> p.id() == null ? initialData.projectId() == null
                        : p.id().equals(initialData.projectId()))
                .findFirst().ifPresent(projectBox::setValue);

        // Folder selector
        ObservableList<FolderProfile> folderItems = FXCollections.observableArrayList();
        folderItems.add(new FolderProfile(null, i18n.get("folder.none"), null, null, 0));
        folderItems.addAll(folders);
        ComboBox<FolderProfile> folderBox = new ComboBox<>(folderItems);
        folderBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(FolderProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        folderBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(FolderProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        folderItems.stream()
                .filter(f -> f.id() == null ? initialData.folderId() == null
                        : f.id().equals(initialData.folderId()))
                .findFirst().ifPresent(folderBox::setValue);

        // Vault entry selector
        VaultEntryProfile noneEntry = new VaultEntryProfile(null, i18n.get("vault.select.none"), null, null, null, null);
        ObservableList<VaultEntryProfile> vaultItems = FXCollections.observableArrayList();
        vaultItems.add(noneEntry);
        ComboBox<VaultEntryProfile> vaultBox = new ComboBox<>(vaultItems);
        vaultBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(VaultEntryProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                if (item.id() == null) {
                    setText(item.name()); // "None (Manual)"
                } else {
                    setText(item.name() + " (" + (item.authenticationType() == AuthenticationType.PASSWORD ? "PWD" : "KEY") + ")");
                }
            }
        });
        vaultBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(VaultEntryProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                if (item.id() == null) {
                    setText(item.name());
                } else {
                    setText(item.name() + " (" + (item.authenticationType() == AuthenticationType.PASSWORD ? "PWD" : "KEY") + ")");
                }
            }
        });

        // Load vault entries for current project
        Runnable reloadVaultEntries = () -> {
            ProjectProfile selectedProject = projectBox.getValue();
            String pid = selectedProject != null ? selectedProject.id() : null;
            vaultItems.setAll(noneEntry);
            vaultItems.addAll(vaultService.listByProject(pid));
            // Pre-select if initial data has vaultEntryId
            if (initialData.vaultEntryId() != null) {
                vaultItems.stream()
                        .filter(v -> v.id() != null && v.id().equals(initialData.vaultEntryId()))
                        .findFirst().ifPresent(vaultBox::setValue);
            } else {
                vaultBox.setValue(noneEntry);
            }
        };
        projectBox.valueProperty().addListener((o, ov, nv) -> reloadVaultEntries.run());
        reloadVaultEntries.run();

        Hyperlink manageVaultLink = new Hyperlink(i18n.get("connection.vaultManage"));
        manageVaultLink.setStyle("-fx-font-size: 0.85em;");
        manageVaultLink.setOnAction(e -> {
            ProjectProfile selectedProject = projectBox.getValue();
            String pid = selectedProject != null ? selectedProject.id() : null;
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            com.jlshell.ui.dialog.VaultManagerDialog.show(
                    stage, vaultService, i18n, themeService, pid, projects, null);
            reloadVaultEntries.run();
        });

        // Vault selection → auto-fill & lock auth fields
        String[] vaultEntryIdHolder = {initialData.vaultEntryId()};
        String[] keyContentHolder = {initialData.keyContent()};

        vaultBox.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null || nv.id() == null) {
                // "None (Manual)" — unlock fields
                vaultEntryIdHolder[0] = null;
                keyContentHolder[0] = null;
                passwordField.setDisable(false);
                privateKeyPathField.setDisable(false);
                passphraseField.setDisable(false);
                authTypeBox.setDisable(false);
            } else {
                // Vault entry selected — decrypt and fill
                vaultEntryIdHolder[0] = nv.id();

                // If CUSTOM mode and vault is locked, prompt for master password
                if (nv.encryptionMode() == com.jlshell.data.entity.VaultEncryptionMode.CUSTOM
                        && !vaultService.getKeyService().isUnlocked()) {
                    com.jlshell.ui.dialog.VaultManagerDialog.PasswordDialog pwdDialog =
                            new com.jlshell.ui.dialog.VaultManagerDialog.PasswordDialog(
                                    (Stage) dialogPane.getScene().getWindow(),
                                    i18n, themeService, i18n.get("vault.unlock.title"), false);
                    java.util.Optional<char[]> pwdResult = pwdDialog.showAndWait();
                    if (pwdResult.isPresent() && vaultService.getKeyService().unlock(pwdResult.get())) {
                        java.util.Arrays.fill(pwdResult.get(), '\0');
                    } else {
                        // Failed to unlock — revert to "None"
                        vaultBox.setValue(noneEntry);
                        return;
                    }
                }

                try {
                    com.jlshell.ui.model.VaultEntryFormData vaultForm = vaultService.loadForm(nv.id());
                    authTypeBox.setValue(vaultForm.authenticationType());
                    passwordField.setText(vaultForm.password() != null ? vaultForm.password() : "");
                    passphraseField.setText(vaultForm.passphrase() != null ? vaultForm.passphrase() : "");
                    privateKeyPathField.setText(vaultForm.privateKeyPath() != null ? vaultForm.privateKeyPath() : "");
                    keyContentHolder[0] = vaultForm.keyContent();
                    // Lock fields
                    passwordField.setDisable(true);
                    privateKeyPathField.setDisable(true);
                    passphraseField.setDisable(true);
                    authTypeBox.setDisable(true);
                } catch (Exception ex) {
                    // If vault entry can't be loaded, fall back to manual
                    vaultEntryIdHolder[0] = null;
                    keyContentHolder[0] = null;
                    passwordField.setDisable(false);
                    privateKeyPathField.setDisable(false);
                    passphraseField.setDisable(false);
                    authTypeBox.setDisable(false);
                }
            }
        });

        // Trigger initial vault selection if vaultEntryId is set
        if (initialData.vaultEntryId() != null) {
            vaultItems.stream()
                    .filter(v -> v.id() != null && v.id().equals(initialData.vaultEntryId()))
                    .findFirst().ifPresent(v -> vaultBox.setValue(v));
        }

        GridPane form = new GridPane();
        form.getStyleClass().add("form-grid");
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(20));

        // SSH-only rows — labels and fields we'll show/hide
        Label hostLabel = new Label(i18n.get("connection.host"));
        Label portLabel = new Label(i18n.get("connection.port"));
        Label usernameLabel = new Label(i18n.get("connection.username"));
        Label authTypeLabel = new Label(i18n.get("connection.authType"));
        Label passwordLabel = new Label(i18n.get("connection.password"));
        Label privateKeyLabel = new Label(i18n.get("connection.privateKey"));
        Label passphraseLabel = new Label(i18n.get("connection.passphrase"));
        Label hostKeyLabel = new Label(i18n.get("connection.hostKeyMode"));
        Label remotePathLabel = new Label(i18n.get("connection.remotePath"));

        int row = 0;
        form.add(new Label(i18n.get("connection.type")), 0, row);
        form.add(typeBox, 1, row++);
        form.add(new Label(i18n.get("connection.displayName")), 0, row);
        form.add(displayNameField, 1, row++);
        form.add(hostLabel, 0, row);
        form.add(hostField, 1, row++);
        form.add(portLabel, 0, row);
        form.add(portField, 1, row++);
        form.add(usernameLabel, 0, row);
        form.add(usernameField, 1, row++);
        form.add(new Label(i18n.get("connection.vaultEntry")), 0, row);
        HBox vaultRow = new HBox(8, vaultBox, manageVaultLink);
        vaultRow.setAlignment(Pos.CENTER_LEFT);
        form.add(vaultRow, 1, row++);
        form.add(authTypeLabel, 0, row);
        form.add(authTypeBox, 1, row++);
        form.add(passwordLabel, 0, row);
        form.add(passwordField, 1, row++);
        form.add(privateKeyLabel, 0, row);
        form.add(privateKeyPathField, 1, row++);
        form.add(passphraseLabel, 0, row);
        form.add(passphraseField, 1, row++);
        form.add(hostKeyLabel, 0, row);
        form.add(hostKeyBox, 1, row++);
        form.add(remotePathLabel, 0, row);
        form.add(defaultRemotePathField, 1, row++);
        form.add(new Label(i18n.get("connection.description")), 0, row);
        form.add(descriptionArea, 1, row++);
        form.add(new Label(i18n.get("project.field.name")), 0, row);
        form.add(projectBox, 1, row++);
        form.add(new Label(i18n.get("folder.field.name")), 0, row);
        form.add(folderBox, 1, row++);
        form.add(favoriteBox, 1, row);

        // Show/hide SSH fields based on type
        javafx.beans.value.ChangeListener<ConnectionType> typeListener = (o, ov, nv) -> {
            boolean isSsh = nv == ConnectionType.SSH;
            hostLabel.setVisible(isSsh); hostField.setVisible(isSsh);
            portLabel.setVisible(isSsh); portField.setVisible(isSsh);
            usernameLabel.setVisible(isSsh); usernameField.setVisible(isSsh);
            authTypeLabel.setVisible(isSsh); authTypeBox.setVisible(isSsh);
            passwordLabel.setVisible(isSsh); passwordField.setVisible(isSsh);
            privateKeyLabel.setVisible(isSsh); privateKeyPathField.setVisible(isSsh);
            passphraseLabel.setVisible(isSsh); passphraseField.setVisible(isSsh);
            hostKeyLabel.setVisible(isSsh); hostKeyBox.setVisible(isSsh);
            remotePathLabel.setVisible(isSsh); defaultRemotePathField.setVisible(isSsh);
            vaultRow.setVisible(isSsh); vaultRow.setManaged(isSsh);
        };
        typeBox.valueProperty().addListener(typeListener);
        typeListener.changed(null, null, typeBox.getValue());

        // Auth type disable binding (only when not locked by vault)
        passwordField.disableProperty().bind(
                Bindings.createBooleanBinding(() ->
                        vaultBox.getValue() != null && vaultBox.getValue().id() != null
                                ? true
                                : authTypeBox.getValue() == AuthenticationType.PRIVATE_KEY,
                        vaultBox.valueProperty(), authTypeBox.valueProperty())
        );
        privateKeyPathField.disableProperty().bind(
                Bindings.createBooleanBinding(() ->
                        vaultBox.getValue() != null && vaultBox.getValue().id() != null
                                ? true
                                : authTypeBox.getValue() == AuthenticationType.PASSWORD,
                        vaultBox.valueProperty(), authTypeBox.valueProperty())
        );
        passphraseField.disableProperty().bind(
                Bindings.createBooleanBinding(() ->
                        vaultBox.getValue() != null && vaultBox.getValue().id() != null
                                ? true
                                : authTypeBox.getValue() == AuthenticationType.PASSWORD,
                        vaultBox.valueProperty(), authTypeBox.valueProperty())
        );

        // Test connection button + result label
        Button testBtn = new Button(i18n.get("action.testConnection"));
        testBtn.getStyleClass().add("button-primary");
        testBtn.setStyle("-fx-padding:4 12;-fx-font-size: 0.92em;");
        Label testResult = new Label();
        testResult.setStyle("-fx-font-size: 0.85em;");

        Runnable updateTestBtnVisibility = () -> {
            boolean isSsh = typeBox.getValue() == ConnectionType.SSH;
            testBtn.setVisible(isSsh);
            testBtn.setManaged(isSsh);
        };
        typeBox.valueProperty().addListener((o, ov, nv) -> updateTestBtnVisibility.run());
        updateTestBtnVisibility.run();

        testBtn.setOnAction(e -> {
            String host = hostField.getText() == null ? "" : hostField.getText().trim();
            String portText = portField.getText() == null ? "" : portField.getText().trim();
            String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
            if (host.isEmpty() || username.isEmpty()) {
                testResult.setTextFill(Color.RED);
                testResult.setText(i18n.get("testConnection.fillFields"));
                return;
            }
            int port;
            try { port = Integer.parseInt(portText.isEmpty() ? "22" : portText); }
            catch (NumberFormatException ex) {
                testResult.setTextFill(Color.RED);
                testResult.setText(i18n.get("testConnection.invalidPort"));
                return;
            }

            AuthenticationType authType = authTypeBox.getValue();
            String pwd = passwordField.getText() == null ? "" : passwordField.getText();
            String keyPath = privateKeyPathField.getText() == null ? "" : privateKeyPathField.getText().trim();
            String passphr = passphraseField.getText() == null ? "" : passphraseField.getText();

            // If vault entry selected, validate via vault
            if (vaultEntryIdHolder[0] != null) {
                // Vault handles credential — no need to validate password/keyPath here
            } else {
                if (authType == AuthenticationType.PASSWORD && pwd.isEmpty()) {
                    testResult.setTextFill(Color.RED);
                    testResult.setText(i18n.get("testConnection.fillFields"));
                    return;
                }
                if (authType == AuthenticationType.PRIVATE_KEY && keyPath.isEmpty()) {
                    testResult.setTextFill(Color.RED);
                    testResult.setText(i18n.get("testConnection.fillFields"));
                    return;
                }
            }

            testBtn.setDisable(true);
            testResult.setTextFill(Color.GRAY);
            testResult.setText(i18n.get("testConnection.testing"));

            // Build test request — if vault entry, use vault credentials
            ConnectionRequest request;
            if (vaultEntryIdHolder[0] != null) {
                try {
                    CredentialPayload credPayload = vaultService.toCredentialPayload(vaultEntryIdHolder[0]);
                    request = new ConnectionRequest(
                            displayNameField.getText() == null ? "" : displayNameField.getText().trim(),
                            new ConnectionTarget(host, port, username, Duration.ofSeconds(connectTimeoutSeconds), null),
                            authType == AuthenticationType.PASSWORD ? AuthenticationMethod.PASSWORD : AuthenticationMethod.PRIVATE_KEY,
                            credPayload,
                            hostKeyBox.getValue()
                    );
                } catch (Exception ex) {
                    testBtn.setDisable(false);
                    testResult.setTextFill(Color.RED);
                    testResult.setText(i18n.get("testConnection.failed", ex.getMessage()));
                    return;
                }
            } else {
                request = buildTestRequest(
                        displayNameField.getText() == null ? "" : displayNameField.getText().trim(),
                        host, port, username,
                        authType, pwd, keyPath, passphr,
                        hostKeyBox.getValue(),
                        connectTimeoutSeconds);
            }

            testConnection.apply(request).whenComplete((msg, ex) -> Platform.runLater(() -> {
                testBtn.setDisable(false);
                if (ex != null) {
                    testResult.setTextFill(Color.RED);
                    String err = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    testResult.setText(i18n.get("testConnection.failed", err));
                } else {
                    testResult.setTextFill(Color.web("#16a34a"));
                    testResult.setText(msg != null ? msg : i18n.get("testConnection.success"));
                }
            }));
        });

        testResult.setWrapText(true);
        testResult.setMaxWidth(Double.MAX_VALUE);

        VBox testRow = new VBox(4, testBtn, testResult);
        testRow.setPadding(new Insets(4, 20, 8, 20));

        VBox content = new VBox(form, testRow);
        dialogPane.setContent(content);

        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    if (typeBox.getValue() == ConnectionType.LOCAL_SHELL) {
                        return displayNameField.getText().isBlank();
                    }
                    return hostField.getText().isBlank() || usernameField.getText().isBlank();
                },
                typeBox.valueProperty(),
                hostField.textProperty(),
                usernameField.textProperty(),
                displayNameField.textProperty()
        ));

        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) return null;
            ProjectProfile selectedProject = projectBox.getValue();
            FolderProfile selectedFolder = folderBox.getValue();
            return new ConnectionFormData(
                    initialData.id(),
                    displayNameField.getText(),
                    hostField.getText(),
                    Integer.parseInt(portField.getText().isBlank() ? "22" : portField.getText()),
                    usernameField.getText(),
                    authTypeBox.getValue(),
                    passwordField.getText(),
                    privateKeyPathField.getText(),
                    passphraseField.getText(),
                    hostKeyBox.getValue(),
                    descriptionArea.getText(),
                    defaultRemotePathField.getText(),
                    favoriteBox.isSelected(),
                    selectedProject != null ? selectedProject.id() : null,
                    typeBox.getValue(),
                    selectedFolder != null ? selectedFolder.id() : null,
                    vaultEntryIdHolder[0],
                    keyContentHolder[0]
            );
        });

        return dialog.showAndWait();
    }

    private static ConnectionRequest buildTestRequest(
            String displayName, String host, int port, String username,
            AuthenticationType authType, String password,
            String privateKeyPath, String passphrase,
            HostKeyVerificationMode hostKeyMode,
            int timeoutSeconds) {

        CredentialPayload credential;
        if (authType == AuthenticationType.PASSWORD) {
            credential = CredentialPayload.forPassword(
                    password.toCharArray());
        } else {
            Path keyPath = Path.of(privateKeyPath);
            credential = CredentialPayload.forPrivateKey(
                    keyPath,
                    passphrase.toCharArray());
        }

        return new ConnectionRequest(
                displayName,
                new ConnectionTarget(host, port, username, Duration.ofSeconds(timeoutSeconds), null),
                authType == AuthenticationType.PASSWORD ? AuthenticationMethod.PASSWORD : AuthenticationMethod.PRIVATE_KEY,
                credential,
                hostKeyMode
        );
    }
}
