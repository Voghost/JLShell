package com.jlshell.ui.dialog;

import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 项目管理对话框：创建、重命名、删除项目。
 */
public class ProjectManagerDialog {

    private ProjectManagerDialog() {}

    public static void show(Stage owner, ConnectionProfileService service, I18nService i18n) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("project.manage.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(
                ProjectManagerDialog.class.getResource("/css/dark-theme.css").toExternalForm());
        dialog.getDialogPane().setPrefWidth(500);
        dialog.getDialogPane().setPrefHeight(400);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ObservableList<ProjectProfile> items = FXCollections.observableArrayList(service.listProjects());
        ListView<ProjectProfile> listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        listView.setPrefHeight(200);

        TextField nameField = new TextField();
        nameField.setPromptText(i18n.get("project.field.name"));
        TextArea descField = new TextArea();
        descField.setPromptText(i18n.get("project.field.description"));
        descField.setPrefRowCount(2);

        listView.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                nameField.setText(nv.name());
                descField.setText(nv.description() != null ? nv.description() : "");
            }
        });

        Button saveBtn = new Button(i18n.get("project.action.save"));
        Button newBtn = new Button(i18n.get("project.action.new"));
        Button deleteBtn = new Button(i18n.get("project.action.delete"));

        newBtn.setOnAction(e -> {
            listView.getSelectionModel().clearSelection();
            nameField.clear();
            descField.clear();
            nameField.requestFocus();
        });

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) return;
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            String id = selected != null ? selected.id() : null;
            ProjectProfile saved = service.saveProject(id, name, descField.getText().trim());
            items.setAll(service.listProjects());
            items.stream().filter(p -> p.id().equals(saved.id()))
                    .findFirst().ifPresent(listView.getSelectionModel()::select);
        });

        deleteBtn.setOnAction(e -> {
            ProjectProfile selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("project.action.deleteConfirm").replace("{0}", selected.name()),
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
                service.deleteProject(selected.id());
                items.setAll(service.listProjects());
                nameField.clear();
                descField.clear();
            });
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(8, 0, 0, 0));
        form.add(new Label(i18n.get("project.field.name")), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label(i18n.get("project.field.description")), 0, 1);
        form.add(descField, 1, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(descField, Priority.ALWAYS);

        HBox buttons = new HBox(8, newBtn, saveBtn, deleteBtn);

        VBox content = new VBox(8, listView, form, buttons);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }
}
