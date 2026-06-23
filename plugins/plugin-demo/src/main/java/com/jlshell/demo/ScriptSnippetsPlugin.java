package com.jlshell.demo;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.RpcRequest;
import com.jlshell.plugin.api.rpc.RpcResponse;
import com.jlshell.plugin.api.storage.PluginStorage;
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
    public String displayName(Locale locale) {
        return getBundle(locale).getString("plugin.name");
    }

    @Override
    public String description(Locale locale) {
        return getBundle(locale).getString("plugin.description");
    }

    private ResourceBundle getBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle("com.jlshell.demo.messages", locale);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("com.jlshell.demo.messages", Locale.ENGLISH);
        }
    }

    @Override
    public void activate(PluginContext context) {
        this.activeContext = context;
        PluginView view = view();
        if (view != null) {
            context.openTab(displayName(context.locale()), view.createView(context));
        }
        // 注册 readConfig 能力 — 旧 host 无 capabilityRegistry 时静默失败，不影响插件其余功能
        try {
            context.capabilityRegistry().register(
                com.jlshell.plugin.api.rpc.Capability.builder("readConfig")
                    .description("Read a remote file and return its text content.")
                    .requiresSession(true)
                    .handler((args, capCtx) -> {
                        String path = args != null && args.isJsonObject()
                                ? args.getAsJsonObject().get("path").getAsString() : null;
                        if (path == null || path.isBlank()) {
                            return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalArgumentException("path required"));
                        }
                        return capCtx.sshSession().orElseThrow().fileExplorer().readFile(path)
                                .thenApply(bytes -> {
                                    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                                    o.addProperty("path", path);
                                    o.addProperty("content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                    return (com.google.gson.JsonElement) o;
                                });
                    })
                    .build());
        } catch (Throwable t) {
            // 旧 host 无 capabilityRegistry（default no-op）— register 静默失败
        }
    }

    @Override
    public void deactivate() {
        if (activeContext != null) {
            activeContext.closeTab();
            activeContext = null;
        }
    }

    @Override public PluginView view() { return this; }

    // ── PluginView ───────────────────────────────────────────────────────────

    @Override
    public Node createView(PluginContext context) {
        ResourceBundle bundle = getBundle(context.locale());

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
        descArea.setPromptText(bundle.getString("prompt.selectSnippet"));

        // Right bottom: output
        Label outputLabel = new Label(bundle.getString("label.output"));
        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button runButton = new Button(bundle.getString("button.run"));
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
                outputArea.setText(bundle.getString("status.running"));
                CompletableFuture<com.jlshell.plugin.api.model.CommandOutput> future =
                        ssh.commandExecutor().execute(selected.command());
                future.whenComplete((output, err) -> Platform.runLater(() -> {
                    runButton.setDisable(false);
                    if (err != null) {
                        outputArea.setText("Error: " + err.getMessage());
                        context.showNotification(bundle.getString("error.commandFailed").replace("{0}", err.getMessage()), NotificationLevel.ERROR);
                    } else {
                        String result = output.stdout().isBlank() ? output.stderr() : output.stdout();
                        outputArea.setText(result);
                    }
                }));
            }, () -> {
                outputArea.setText(bundle.getString("status.noSession"));
                context.showNotification(bundle.getString("error.noSession"), NotificationLevel.WARNING);
            });
        });

        HBox buttonBar = new HBox(runButton);

        // ── Fetch Metrics：通过 CapabilityBus 调用 sysmon 的 getMetrics 能力 ──
        CapabilityBus bus = context.capabilityBus();
        if (bus != null) {
            Button metricsBtn = new Button("📊 Fetch Metrics");
            metricsBtn.setStyle("-fx-font-size: 11px;");
            metricsBtn.setTooltip(new javafx.scene.control.Tooltip(
                    "Fetch system metrics from System Monitor plugin via inter-plugin RPC"));
            metricsBtn.setOnAction(e -> {
                String sid = context.sshSession().map(com.jlshell.plugin.api.SshSessionContext::sessionId).orElse(null);
                if (sid == null) {
                    outputArea.setText("No session context available for RPC.");
                    return;
                }
                metricsBtn.setDisable(true);
                outputArea.setText("Fetching metrics via RPC…");
                RpcRequest req = new RpcRequest(sid, "com.jlshell.sysmon", "getMetrics", null, "snippets-" + System.nanoTime());
                bus.invoke(req).whenComplete((resp, err) -> Platform.runLater(() -> {
                    metricsBtn.setDisable(false);
                    if (err != null) {
                        outputArea.setText("RPC error: " + err.getMessage());
                    } else if (resp.error() != null) {
                        outputArea.setText("RPC error: " + resp.error().message());
                    } else if (resp.result() != null && resp.result().isJsonObject()) {
                        com.google.gson.JsonObject m = resp.result().getAsJsonObject();
                        StringBuilder sb = new StringBuilder();
                        sb.append("═══ System Metrics (via RPC) ═══\n\n");
                        sb.append(String.format("  CPU:      %.1f%% (%d cores, load %.2f)%n",
                                m.get("cpuUsage").getAsDouble(),
                                m.get("cpuCores").getAsInt(),
                                m.get("loadAvg1m").getAsDouble()));
                        sb.append(String.format("  Memory:   %s / %s%n",
                                formatBytes(m.get("memUsedBytes").getAsLong()),
                                formatBytes(m.get("memTotalBytes").getAsLong())));
                        sb.append(String.format("  Network:  ↓%s  ↑%s%n",
                                formatBytes(m.get("netRecvBytes").getAsLong()),
                                formatBytes(m.get("netSentBytes").getAsLong())));
                        if (m.has("disks") && m.get("disks").isJsonArray()) {
                            sb.append("  Disks:\n");
                            for (com.google.gson.JsonElement de : m.get("disks").getAsJsonArray()) {
                                com.google.gson.JsonObject d = de.getAsJsonObject();
                                sb.append(String.format("    %s  %s / %s%n",
                                        d.get("mount").getAsString(),
                                        formatBytes(d.get("usedBytes").getAsLong()),
                                        formatBytes(d.get("totalBytes").getAsLong())));
                            }
                        }
                        outputArea.setText(sb.toString());
                    } else {
                        outputArea.setText("Unexpected RPC response.");
                    }
                }));
            });
            buttonBar.getChildren().add(metricsBtn);
        }

        // ── Storage Test：测试 PluginStorage 持久化 ──
        PluginStorage store = context.storage();
        if (store != null) {
            Button storageBtn = new Button("💾 Storage Test");
            storageBtn.setStyle("-fx-font-size: 11px;");
            storageBtn.setTooltip(new javafx.scene.control.Tooltip(
                    "Test PluginStorage: put, get, keys, remove"));
            storageBtn.setOnAction(e -> {
                StringBuilder sb = new StringBuilder();
                sb.append("═══ PluginStorage Test ═══\n\n");

                // 1. 写入
                String testKey = "test.key";
                String testValue = "Hello from ScriptSnippets! ts=" + System.currentTimeMillis();
                store.put(testKey, testValue);
                sb.append("1. PUT  ").append(testKey).append(" = ").append(testValue).append("\n");

                // 2. 读取
                String read = store.get(testKey);
                sb.append("2. GET  ").append(testKey).append(" → ").append(read).append("\n");
                sb.append("   Match: ").append(testValue.equals(read) ? "✅ YES" : "❌ NO").append("\n");

                // 3. 读取不存在的 key
                String missing = store.get("nonexistent.key");
                sb.append("3. GET  nonexistent.key → ").append(missing).append(" (expect null)\n");

                // 4. 默认值
                String withDefault = store.get("nonexistent.key", "fallback-value");
                sb.append("4. GET  nonexistent.key (default) → ").append(withDefault).append("\n");

                // 5. 列出所有 key
                java.util.Set<String> keys = store.keys();
                sb.append("5. KEYS: ").append(keys).append("\n");

                // 6. 写入多个值
                store.put("prefs.theme", "dark");
                store.put("prefs.fontSize", "14");
                sb.append("6. PUT  prefs.theme=dark, prefs.fontSize=14\n");
                sb.append("   KEYS now: ").append(store.keys()).append("\n");

                // 7. 删除
                store.remove("prefs.fontSize");
                sb.append("7. REMOVE prefs.fontSize\n");
                sb.append("   GET prefs.fontSize → ").append(store.get("prefs.fontSize")).append(" (expect null)\n");
                sb.append("   KEYS now: ").append(store.keys()).append("\n");

                // 8. 覆盖写入
                store.put(testKey, "updated-value");
                sb.append("8. PUT  ").append(testKey).append(" = updated-value\n");
                sb.append("   GET → ").append(store.get(testKey)).append("\n");

                sb.append("\n✅ All storage operations completed!");
                outputArea.setText(sb.toString());
            });
            buttonBar.getChildren().add(storageBtn);
        }

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

    @Override
    public void onLocaleChanged(Locale locale) {
        if (activeContext != null) {
            activeContext.updateTabTitle(displayName(locale));
        }
    }

    // ── Inner record ─────────────────────────────────────────────────────────

    public record ScriptSnippet(String name, String command, String description) {}

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}