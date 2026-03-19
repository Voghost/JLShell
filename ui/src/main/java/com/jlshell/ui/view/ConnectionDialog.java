package com.jlshell.ui.view;

import java.util.List;
import java.util.Optional;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.service.I18nService;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * 连接编辑对话框。
 */
public final class ConnectionDialog {

    private ConnectionDialog() {
    }

    public static Optional<ConnectionFormData> show(
            Window owner, I18nService i18n, ConnectionFormData initialData,
            List<ProjectProfile> projects, List<FolderProfile> folders) {
        Dialog<ConnectionFormData> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(i18n.get(initialData.id() == null ? "dialog.connection.create" : "dialog.connection.edit"));

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);

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
        };
        typeBox.valueProperty().addListener(typeListener);
        // Apply initial state
        typeListener.changed(null, null, typeBox.getValue());

        passwordField.disableProperty().bind(authTypeBox.valueProperty().isEqualTo(AuthenticationType.PRIVATE_KEY));
        privateKeyPathField.disableProperty().bind(authTypeBox.valueProperty().isEqualTo(AuthenticationType.PASSWORD));
        passphraseField.disableProperty().bind(authTypeBox.valueProperty().isEqualTo(AuthenticationType.PASSWORD));

        dialogPane.setContent(form);
        dialogPane.lookupButton(javafx.scene.control.ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
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
            if (buttonType != javafx.scene.control.ButtonType.OK) {
                return null;
            }
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
                    selectedFolder != null ? selectedFolder.id() : null
            );
        });

        return dialog.showAndWait();
    }
}
