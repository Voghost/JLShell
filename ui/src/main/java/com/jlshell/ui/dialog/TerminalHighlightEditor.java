package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.model.TerminalHighlightConfig.Rule;
import com.jlshell.terminal.support.TerminalLogHighlighter;
import com.jlshell.ui.service.I18nService;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import java.util.List;
import java.util.function.Consumer;

/** Shared FX/Swing form; rule drafts are validated with the same matcher used by terminals. */
final class TerminalHighlightEditor {
    private TerminalHighlightEditor() {}
    static VBox create(TerminalHighlightConfig initial, I18nService i18n, Consumer<TerminalHighlightConfig> changed) {
        CheckBox enabled = new CheckBox(i18n.get("highlight.enabled"));
        enabled.setId("settings-highlight-enabled"); enabled.setSelected(initial.enabled());
        var rules = FXCollections.observableArrayList(initial.rules());
        ComboBox<Rule> selected = new ComboBox<>(rules);
        selected.setId("settings-highlight-rule"); selected.setMaxWidth(Double.MAX_VALUE);
        TextField name = new TextField(); name.setId("settings-highlight-name");
        TextField expression = new TextField(); expression.setId("settings-highlight-expression");
        CheckBox active = new CheckBox(i18n.get("highlight.ruleEnabled"));
        CheckBox regex = new CheckBox(i18n.get("highlight.regex"));
        CheckBox ignoreCase = new CheckBox(i18n.get("highlight.ignoreCase"));
        ColorPicker color = new ColorPicker(); color.setId("settings-highlight-color");
        Label error = new Label(); error.setId("settings-highlight-error"); error.getStyleClass().add("settings-hint"); error.setWrapText(true);
        error.visibleProperty().bind(error.textProperty().isNotEmpty()); error.managedProperty().bind(error.visibleProperty());
        Label status = new Label(); status.getStyleClass().add("settings-hint"); status.setWrapText(true);
        status.visibleProperty().bind(status.textProperty().isNotEmpty()); status.managedProperty().bind(status.visibleProperty());
        TextField sample = new TextField("2026-10-30 18:06:00 INFO connected | WARN slow query | ERROR failed | SUCCESS");
        sample.setId("settings-highlight-sample");
        HBox preview = new HBox(0); preview.setId("settings-highlight-preview");
        preview.setBackground(new Background(new BackgroundFill(Color.web("#18191B"), CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
        preview.setStyle("-fx-background-color: #18191B; -fx-padding: 12;");
        ScrollPane previewScroll = new ScrollPane(preview); previewScroll.setFitToHeight(true);
        previewScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        previewScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); previewScroll.setPrefHeight(56);
        Runnable refreshPreview = () -> {
            preview.getChildren().clear();
            var config = new TerminalHighlightConfig(enabled.isSelected(), List.copyOf(rules));
            var spans = new TerminalLogHighlighter(config).match(sample.getText());
            int offset = 0;
            for (var span : spans) {
                if (span.start() > offset) preview.getChildren().add(fragment(sample.getText().substring(offset, span.start()), Color.web("#D4D4D4")));
                preview.getChildren().add(fragment(sample.getText().substring(span.start(), span.end()),
                        Color.rgb(span.color().getRed(), span.color().getGreen(), span.color().getBlue())));
                offset = span.end();
            }
            if (offset < sample.getText().length()) preview.getChildren().add(fragment(sample.getText().substring(offset), Color.web("#D4D4D4")));
        };
        Runnable publish = () -> {
            changed.accept(new TerminalHighlightConfig(enabled.isSelected(), List.copyOf(rules)));
            status.setText(""); refreshPreview.run();
        };
        Runnable load = () -> {
            Rule rule = selected.getValue(); if (rule == null) return;
            name.setText(rule.name()); expression.setText(rule.expression()); active.setSelected(rule.enabled());
            regex.setSelected(rule.regex()); ignoreCase.setSelected(rule.ignoreCase()); color.setValue(Color.web(rule.color()));
            error.setText(""); status.setText("");
        };
        selected.valueProperty().addListener((obs, old, value) -> load.run());
        Button save = new Button(i18n.get("highlight.saveRule")); save.setId("settings-highlight-save");
        save.setOnAction(event -> {
            String hex = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", Math.round(color.getValue().getRed()*255),
                    Math.round(color.getValue().getGreen()*255), Math.round(color.getValue().getBlue()*255));
            Rule next = new Rule(name.getText().trim(), active.isSelected(), regex.isSelected(), ignoreCase.isSelected(), expression.getText(), hex);
            if (next.name().isBlank()) { error.setText(i18n.get("highlight.invalid")); return; }
            try { new TerminalLogHighlighter(new TerminalHighlightConfig(true, List.of(next))); }
            catch (RuntimeException ex) { error.setText(i18n.get("highlight.invalid")); return; }
            int index = selected.getSelectionModel().getSelectedIndex();
            if (index < 0) {
                if (rules.size() >= TerminalHighlightConfig.MAX_RULES) { error.setText(i18n.get("highlight.limit")); return; }
                rules.add(next); index = rules.size()-1;
            } else rules.set(index, next);
            selected.getSelectionModel().select(index); error.setText(""); publish.run();
            status.setText(i18n.get("highlight.saved"));
        });
        Button add = new Button(i18n.get("highlight.add")); add.setOnAction(e -> {
            selected.getSelectionModel().clearSelection(); name.clear(); expression.clear(); active.setSelected(true);
            regex.setSelected(false); ignoreCase.setSelected(true); color.setValue(Color.web("#38BDF8"));
            error.setText(""); status.setText(i18n.get("highlight.draft"));
        });
        Button remove = new Button(i18n.get("highlight.remove")); remove.setOnAction(e -> {
            int index = selected.getSelectionModel().getSelectedIndex();
            if (index >= 0) { rules.remove(index); selected.getSelectionModel().select(Math.min(index, rules.size()-1)); publish.run(); }
        });
        Button up = new Button(i18n.get("highlight.up")); up.setOnAction(e -> {
            int index = selected.getSelectionModel().getSelectedIndex();
            if (index > 0) { Rule rule = rules.remove(index); rules.add(index-1, rule); selected.getSelectionModel().select(index-1); publish.run(); }
        });
        Button reset = new Button(i18n.get("highlight.reset")); reset.setOnAction(e -> {
            rules.setAll(TerminalHighlightConfig.defaults().rules()); selected.getSelectionModel().selectFirst(); publish.run();
        });
        enabled.selectedProperty().addListener((obs, old, value) -> publish.run());
        sample.textProperty().addListener((obs, old, value) -> refreshPreview.run());
        HBox actions = new HBox(8, add, remove, up, reset); actions.setAlignment(Pos.CENTER_LEFT);
        GridPane fields = new GridPane(); fields.setHgap(12); fields.setVgap(10);
        fields.add(new Label(i18n.get("highlight.name")), 0, 0); fields.add(name, 1, 0);
        fields.add(new Label(i18n.get("highlight.expression")), 0, 1); fields.add(expression, 1, 1);
        fields.add(new Label(i18n.get("highlight.color")), 0, 2); fields.add(new HBox(color), 1, 2);
        GridPane.setHgrow(name, Priority.ALWAYS); GridPane.setHgrow(expression, Priority.ALWAYS);
        VBox box = new VBox(10, enabled, selected, actions, fields, new HBox(12, active, regex, ignoreCase),
                new HBox(save), error, status, new Label(i18n.get("highlight.preview")), sample, previewScroll);
        selected.getSelectionModel().selectFirst(); refreshPreview.run();
        return box;
    }
    private static Text fragment(String value, Color color) {
        Text text = new Text(value); text.setFill(color); text.setFont(javafx.scene.text.Font.font("Monospaced", 12)); return text;
    }
}
