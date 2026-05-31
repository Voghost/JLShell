package com.jlshell.demo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Demo plugin: a library of common SSH script snippets.
 * Drop the fat JAR into ~/.jlshell/plugins/ to load externally.
 */
public class ScriptSnippetsPlugin implements JlShellPlugin, PluginView {

    private static final List<ScriptSnippet> SNIPPETS = List.of(
            new ScriptSnippet("Disk Usage",       "df -h",                              "Show disk usage in human-readable format"),
            new ScriptSnippet("Memory",           "free -m",                            "Show memory usage in megabytes"),
            new ScriptSnippet("CPU (top)",        "top -bn1 | head -20",                "One-shot top output, first 20 lines"),
            new ScriptSnippet("Processes",        "ps aux --sort=-%cpu | head -20",     "Top 20 processes by CPU usage"),
            new ScriptSnippet("Network Sockets",  "ss -tulnp",                          "Show listening TCP/UDP sockets with process info"),
            new ScriptSnippet("Uptime",           "uptime",                             "System uptime and load averages"),
            new ScriptSnippet("Last Logins",      "last -n 10",                         "Last 10 login records"),
            new ScriptSnippet("Syslog Tail",      "tail -50 /var/log/syslog",           "Last 50 lines of /var/log/syslog")
    );

    private PluginContext activeContext;

    // ── JlShellPlugin ────────────────────────────────────────────────────────

    @Override public String id()          { return "com.jlshell.demo.script-snippets"; }
    @Override public String displayName() { return "Script Snippets"; }
    @Override public String version()     { return "0.1.0"; }
    @Override public String description() { return "Run common SSH diagnostic commands with one click."; }
    @Override public boolean requiresSshSession() { return true; }

    @Override
    public void activate(PluginContext context) {
        this.activeContext = context;
        PluginView view = view();
        if (view != null) {
            context.openTab(displayName(), view.createView(context));
        }
    }

    @Override
    public void deactivate() {
        if (activeContext != null) {
            activeContext.closeTab();
            activeContext = null;
        }
    }

    @Override
    public PluginView view() { return this; }

    // ── PluginView ───────────────────────────────────────────────────────────

    @Override
    public Node createView(PluginContext context) {
        // Left: snippet list
        ListView<ScriptSnippet> listView = new ListView<>();
        listView.getItems().addAll(SNIPPETS);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ScriptSnippet item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });

        // Right top: description + command preview
        TextArea descArea = new TextArea();
        descArea.setEditable(false);
        descArea.setWrapText(true);
        descArea.setPrefRowCount(4);
        descArea.setPromptText("Select a snippet to see details.");

        // Right bottom: output
        Label outputLabel = new Label("Output:");
        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button runButton = new Button("Run");
        runButton.setDisable(true);

        listView.getSelectionModel().selectedItemProperty().addListener((obs, ov, snippet) -> {
            if (snippet == null) {
                descArea.clear();
                runButton.setDisable(true);
            } else {
                descArea.setText(snippet.description() + "\n\n$ " + snippet.command());
                runButton.setDisable(false);
            }
        });

        runButton.setOnAction(e -> {
            ScriptSnippet selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            context.sshSession().ifPresentOrElse(ssh -> {
                runButton.setDisable(true);
                outputArea.setText("Running...");
                CompletableFuture<com.jlshell.plugin.api.model.CommandOutput> future =
                        ssh.commandExecutor().execute(selected.command());
                future.whenComplete((output, err) -> Platform.runLater(() -> {
                    runButton.setDisable(false);
                    if (err != null) {
                        outputArea.setText("Error: " + err.getMessage());
                        context.showNotification("Command failed: " + err.getMessage(), NotificationLevel.ERROR);
                    } else {
                        String result = output.stdout().isBlank() ? output.stderr() : output.stdout();
                        outputArea.setText(result);
                    }
                }));
            }, () -> {
                outputArea.setText("No SSH session available.");
                context.showNotification("No SSH session available.", NotificationLevel.WARNING);
            });
        });

        HBox buttonBar = new HBox(runButton);
        buttonBar.setPadding(new Insets(4, 0, 0, 0));

        VBox rightPane = new VBox(4, descArea, outputLabel, outputArea, buttonBar);
        rightPane.setPadding(new Insets(8));
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(listView, rightPane);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.3);

        BorderPane root = new BorderPane(split);
        root.setPadding(new Insets(8));
        return root;
    }

    // ── Inner record ─────────────────────────────────────────────────────────

    public record ScriptSnippet(String name, String command, String description) {}
}
