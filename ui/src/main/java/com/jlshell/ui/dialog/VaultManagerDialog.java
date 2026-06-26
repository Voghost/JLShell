package com.jlshell.ui.dialog;

import java.io.File;
import java.util.List;

import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.VaultEntryFormData;
import com.jlshell.ui.model.VaultEntryProfile;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultKeyService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.theme.ThemeService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * 凭据库管理对话框：创建、编辑、删除凭据条目。
 */
public class VaultManagerDialog {

    private VaultManagerDialog() {}

    public static void show(Stage owner, VaultService vaultService, I18nService i18n,
                            ThemeService themeService, String activeProjectId,
                            List<ProjectProfile> projects, Runnable onVaultChanged) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("vault.manager.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().setPrefHeight(560);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VaultKeyService keyService = vaultService.getKeyService();

        // Lock/unlock bar
        Label lockStatus = new Label();
        Button lockBtn = new Button();
        Runnable updateLockUI = () -> {
            boolean unlocked = keyService.isUnlocked();
            boolean configured = keyService.isCustomKeyConfigured();
            if (configured) {
                lockStatus.setText(unlocked ? i18n.get("vault.status.unlocked") : i18n.get("vault.status.locked"));
                lockStatus.setStyle(unlocked ? "-fx-text-fill:#16a34a;-fx-font-size: 0.85em;" : "-fx-text-fill:#ef4444;-fx-font-size: 0.85em;");
                lockBtn.setText(unlocked ? i18n.get("vault.action.lock") : i18n.get("vault.action.unlock"));
                lockBtn.setStyle("-fx-font-size: 0.85em;-fx-padding:2 8;");
            } else {
                lockStatus.setText(i18n.get("vault.status.noMasterPwd"));
                lockStatus.setStyle("-fx-text-fill:#64748b;-fx-font-size: 0.85em;");
                lockBtn.setText(i18n.get("vault.action.setMasterPwd"));
                lockBtn.setStyle("-fx-font-size: 0.85em;-fx-padding:2 8;");
            }
        };
        updateLockUI.run();

        lockBtn.setOnAction(e -> {
            if (keyService.isUnlocked()) {
                keyService.lock();
            } else if (keyService.isCustomKeyConfigured()) {
                // Unlock: prompt for password
                PasswordDialog pwdDialog = new PasswordDialog(
                        (Stage) dialog.getDialogPane().getScene().getWindow(),
                        i18n, themeService, i18n.get("vault.unlock.title"), false);
                pwdDialog.showAndWait().ifPresent(pwd -> {
                    if (keyService.unlock(pwd)) {
                        updateLockUI.run();
                    } else {
                        Alert alert = new Alert(Alert.AlertType.ERROR, i18n.get("vault.unlock.failed"));
                        themeService.applyToDialog(alert);
                        alert.showAndWait();
                    }
                });
            } else {
                // First time: set master password
                PasswordDialog pwdDialog = new PasswordDialog(
                        (Stage) dialog.getDialogPane().getScene().getWindow(),
                        i18n, themeService, i18n.get("vault.setMasterPwd.title"), true);
                pwdDialog.showAndWait().ifPresent(pwd -> {
                    keyService.setupMasterPassword(pwd);
                    updateLockUI.run();
                });
            }
        });

        HBox lockBar = new HBox(8, lockStatus, new Region(), lockBtn);
        lockBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lockBar, Priority.ALWAYS);

        // Project scope selector
        ObservableList<ProjectProfile> projectItems = FXCollections.observableArrayList();
        projectItems.add(new ProjectProfile(null, i18n.get("project.label.default"), null));
        projectItems.addAll(projects);
        ComboBox<ProjectProfile> scopeBox = new ComboBox<>(projectItems);
        scopeBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        scopeBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        projectItems.stream()
                .filter(p -> p.id() == null ? activeProjectId == null : p.id().equals(activeProjectId))
                .findFirst().ifPresent(scopeBox::setValue);

        // Vault entry list
        ObservableList<VaultEntryProfile> items = FXCollections.observableArrayList();
        ListView<VaultEntryProfile> listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final Label nameLbl = new Label();
            private final Label typeBadge = new Label();
            private final Label lockBadge = new Label();
            private final HBox box = new HBox(8, nameLbl, typeBadge, lockBadge);

            {
                typeBadge.setStyle("-fx-font-size: 0.77em;-fx-text-fill:#64748b;");
                lockBadge.setStyle("-fx-font-size: 0.77em;");
                HBox.setHgrow(nameLbl, Priority.ALWAYS);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(VaultEntryProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                nameLbl.setText(item.name());
                typeBadge.setText(item.authenticationType() == AuthenticationType.PASSWORD ? "PWD" : "KEY");
                if (item.encryptionMode() == VaultEncryptionMode.CUSTOM) {
                    lockBadge.setText("🔒"); // 🔒
                    lockBadge.setStyle("-fx-font-size: 0.77em;");
                } else {
                    lockBadge.setText("🔓"); // 🔓
                    lockBadge.setStyle("-fx-font-size: 0.77em;");
                }
                setGraphic(box);
                setText(null);
            }
        });
        listView.setPrefHeight(180);

        // Load items by project
        Runnable reloadList = () -> {
            ProjectProfile selected = scopeBox.getValue();
            String pid = selected != null ? selected.id() : null;
            items.setAll(vaultService.listByProject(pid));
        };
        scopeBox.valueProperty().addListener((o, ov, nv) -> reloadList.run());
        reloadList.run();

        // Edit form
        TextField nameField = new TextField();
        nameField.setPromptText(i18n.get("vault.field.name"));

        ComboBox<AuthenticationType> authTypeBox = new ComboBox<>(
                FXCollections.observableArrayList(AuthenticationType.values()));

        ComboBox<VaultEncryptionMode> encryptionModeBox = new ComboBox<>(
                FXCollections.observableArrayList(VaultEncryptionMode.values()));

        PasswordField passwordField = new PasswordField();
        TextArea keyContentArea = new TextArea();
        keyContentArea.setPrefRowCount(4);
        keyContentArea.setPromptText(i18n.get("vault.field.keyContent"));

        TextField keyPathField = new TextField();
        keyPathField.setPromptText(i18n.get("vault.field.keyPath"));

        Button importKeyBtn = new Button(i18n.get("vault.action.importKey"));
        importKeyBtn.setStyle("-fx-font-size: 0.85em;-fx-padding:2 8;");

        PasswordField passphraseField = new PasswordField();

        ComboBox<ProjectProfile> projectBox = new ComboBox<>(FXCollections.observableArrayList(projectItems));
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

        // Auth type visibility
        Runnable updateAuthFields = () -> {
            boolean isPassword = authTypeBox.getValue() == AuthenticationType.PASSWORD;
            boolean isKey = authTypeBox.getValue() == AuthenticationType.PRIVATE_KEY;
            passwordField.setVisible(isPassword);
            passwordField.setManaged(isPassword);
            keyContentArea.setVisible(isKey);
            keyContentArea.setManaged(isKey);
            keyPathField.setVisible(isKey);
            keyPathField.setManaged(isKey);
            importKeyBtn.setVisible(isKey);
            importKeyBtn.setManaged(isKey);
            passphraseField.setVisible(isKey);
            passphraseField.setManaged(isKey);
        };
        authTypeBox.valueProperty().addListener((o, ov, nv) -> updateAuthFields.run());

        // CUSTOM mode warning
        Label customModeHint = new Label();
        customModeHint.setStyle("-fx-font-size: 0.77em;-fx-text-fill:#f59e0b;");
        encryptionModeBox.valueProperty().addListener((o, ov, nv) -> {
            if (nv == VaultEncryptionMode.CUSTOM) {
                if (!keyService.isUnlocked()) {
                    customModeHint.setText(i18n.get("vault.hint.needUnlock"));
                } else {
                    customModeHint.setText(i18n.get("vault.hint.customMode"));
                }
            } else {
                customModeHint.setText("");
            }
        });

        // List selection → populate form
        listView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                try {
                    VaultEntryFormData form = vaultService.loadForm(nv.id());
                    nameField.setText(form.name());
                    authTypeBox.setValue(form.authenticationType());
                    encryptionModeBox.setValue(form.encryptionMode());
                    passwordField.setText(form.password() != null ? form.password() : "");
                    keyContentArea.setText(form.keyContent() != null ? form.keyContent() : "");
                    keyPathField.setText(form.privateKeyPath() != null ? form.privateKeyPath() : "");
                    passphraseField.setText(form.passphrase() != null ? form.passphrase() : "");
                    projectItems.stream()
                            .filter(p -> p.id() == null ? form.projectId() == null : p.id().equals(form.projectId()))
                            .findFirst().ifPresent(projectBox::setValue);
                } catch (IllegalStateException e) {
                    // CUSTOM entry but vault is locked — show minimal info
                    nameField.setText(nv.name());
                    authTypeBox.setValue(nv.authenticationType());
                    encryptionModeBox.setValue(nv.encryptionMode());
                    passwordField.clear();
                    keyContentArea.clear();
                    keyPathField.setText(nv.privateKeyPath() != null ? nv.privateKeyPath() : "");
                    passphraseField.clear();
                }
                updateAuthFields.run();
            }
        });

        // Import key file
        importKeyBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(i18n.get("vault.action.importKey"));
            File file = fc.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) {
                String projectId = projectBox.getValue() != null ? projectBox.getValue().id() : null;
                VaultEntryFormData imported = vaultService.importKeyFile(file.getAbsolutePath(), projectId);
                nameField.setText(imported.name());
                keyContentArea.setText(imported.keyContent());
                keyPathField.setText(imported.privateKeyPath());
                passphraseField.setText(imported.passphrase() != null ? imported.passphrase() : "");
            }
        });

        // Buttons
        Button newBtn = new Button(i18n.get("vault.action.new"));
        Button saveBtn = new Button(i18n.get("vault.action.save"));
        saveBtn.getStyleClass().add("button-primary");
        Button deleteBtn = new Button(i18n.get("vault.action.delete"));

        newBtn.setOnAction(e -> {
            listView.getSelectionModel().clearSelection();
            nameField.clear();
            authTypeBox.setValue(AuthenticationType.PASSWORD);
            encryptionModeBox.setValue(VaultEncryptionMode.SYSTEM);
            passwordField.clear();
            keyContentArea.clear();
            keyPathField.clear();
            passphraseField.clear();
            String pid = scopeBox.getValue() != null ? scopeBox.getValue().id() : null;
            projectItems.stream()
                    .filter(p -> p.id() == null ? pid == null : p.id().equals(pid))
                    .findFirst().ifPresent(projectBox::setValue);
            nameField.requestFocus();
            updateAuthFields.run();
        });

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) return;
            VaultEntryProfile selected = listView.getSelectionModel().getSelectedItem();
            String id = selected != null ? selected.id() : null;
            String projectId = projectBox.getValue() != null ? projectBox.getValue().id() : null;
            VaultEncryptionMode mode = encryptionModeBox.getValue();
            if (mode == VaultEncryptionMode.CUSTOM && !keyService.isUnlocked()) {
                Alert alert = new Alert(Alert.AlertType.WARNING, i18n.get("vault.hint.needUnlock"));
                themeService.applyToDialog(alert);
                alert.showAndWait();
                return;
            }
            VaultEntryFormData formData = new VaultEntryFormData(
                    id, name, authTypeBox.getValue(), mode,
                    passwordField.getText(),
                    passphraseField.getText(),
                    keyContentArea.getText(),
                    keyPathField.getText(),
                    projectId
            );
            try {
                VaultEntryProfile saved = vaultService.save(formData);
                reloadList.run();
                items.stream().filter(p -> p.id().equals(saved.id()))
                        .findFirst().ifPresent(listView.getSelectionModel()::select);
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        i18n.get("vault.status.saveFailed", ex.getMessage()));
                themeService.applyToDialog(alert);
                alert.showAndWait();
            }
        });

        deleteBtn.setOnAction(e -> {
            VaultEntryProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("vault.action.deleteConfirm").replace("{0}", selected.name()),
                    ButtonType.YES, ButtonType.NO);
            themeService.applyToDialog(confirm);
            confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
                vaultService.delete(selected.id());
                reloadList.run();
                nameField.clear();
                passwordField.clear();
                keyContentArea.clear();
                keyPathField.clear();
                passphraseField.clear();
                if (onVaultChanged != null) onVaultChanged.run();
            });
        });

        // Layout
        HBox scopeRow = new HBox(8, new Label(i18n.get("vault.label.projectScope")), scopeBox);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.setPadding(new Insets(0, 0, 4, 0));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(8, 0, 0, 0));

        int row = 0;
        form.add(new Label(i18n.get("vault.field.name")), 0, row);
        form.add(nameField, 1, row++);
        form.add(new Label(i18n.get("vault.field.authType")), 0, row);
        form.add(authTypeBox, 1, row++);
        HBox encModeRow = new HBox(8, encryptionModeBox, customModeHint);
        encModeRow.setAlignment(Pos.CENTER_LEFT);
        form.add(new Label(i18n.get("vault.field.encMode")), 0, row);
        form.add(encModeRow, 1, row++);
        form.add(new Label(i18n.get("vault.field.password")), 0, row);
        form.add(passwordField, 1, row++);
        form.add(new Label(i18n.get("vault.field.keyContent")), 0, row);
        form.add(keyContentArea, 1, row++);
        HBox keyPathRow = new HBox(8, keyPathField, importKeyBtn);
        HBox.setHgrow(keyPathField, Priority.ALWAYS);
        form.add(new Label(i18n.get("vault.field.keyPath")), 0, row);
        form.add(keyPathRow, 1, row++);
        form.add(new Label(i18n.get("vault.field.passphrase")), 0, row);
        form.add(passphraseField, 1, row++);
        form.add(new Label(i18n.get("vault.field.project")), 0, row);
        form.add(projectBox, 1, row);

        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(keyContentArea, Priority.ALWAYS);
        GridPane.setHgrow(passphraseField, Priority.ALWAYS);

        HBox editButtons = new HBox(8, newBtn, saveBtn, deleteBtn);
        VBox content = new VBox(8, lockBar, scopeRow, listView, form, editButtons);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        updateAuthFields.run();
        dialog.showAndWait();

        if (onVaultChanged != null) onVaultChanged.run();
    }

    // ── Password input dialog ─────────────────────────────────────────

    public static class PasswordDialog extends Dialog<char[]> {
        public PasswordDialog(Stage owner, I18nService i18n, ThemeService themeService, String title, boolean confirm) {
            setTitle(title);
            setHeaderText(null);
            if (owner != null) initOwner(owner);
            themeService.applyToDialog(this);
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            PasswordField pwdField = new PasswordField();
            pwdField.setPromptText(i18n.get("vault.field.masterPwd"));
            PasswordField confirmField = new PasswordField();
            confirmField.setPromptText(i18n.get("vault.field.masterPwdConfirm"));

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));
            grid.add(new Label(i18n.get("vault.field.masterPwd")), 0, 0);
            grid.add(pwdField, 1, 0);
            if (confirm) {
                grid.add(new Label(i18n.get("vault.field.masterPwdConfirm")), 0, 1);
                grid.add(confirmField, 1, 1);
            }
            GridPane.setHgrow(pwdField, Priority.ALWAYS);
            GridPane.setHgrow(confirmField, Priority.ALWAYS);
            getDialogPane().setContent(grid);

            if (confirm) {
                getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(
                        pwdField.textProperty().isEmpty().or(confirmField.textProperty().isEmpty()));
            } else {
                getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(pwdField.textProperty().isEmpty());
            }

            setResultConverter(btn -> {
                if (btn != ButtonType.OK) return null;
                if (confirm && !pwdField.getText().equals(confirmField.getText())) {
                    return null;
                }
                return pwdField.getText().toCharArray();
            });
        }
    }
}
