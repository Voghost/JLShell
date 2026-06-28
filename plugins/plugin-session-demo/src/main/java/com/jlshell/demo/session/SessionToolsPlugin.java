package com.jlshell.demo.session;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.rpc.Capability;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Session-level demo plugin. It is activated inside a workspace session.
 */
public class SessionToolsPlugin implements JlShellPlugin, PluginView {

    private PluginContext context;

    @Override public String id() { return "com.jlshell.demo.session-tools"; }
    @Override public String displayName() { return "Session Tools Demo"; }
    @Override public String version() { return "0.1.0"; }
    @Override public String author() { return "JLShell"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
    @Override public String description() {
        return "Demonstrates a session plugin view, SSH access, storage, and session capability.";
    }
    @Override public boolean requiresSshSession() { return true; }

    @Override
    public void activate(PluginContext context) {
        this.context = context;
        context.capabilityRegistry().register(Capability.builder("readTextFile")
                .description("Read a remote text file through the active SSH session.")
                .requiresSession(true)
                .handler((args, capCtx) -> {
                    String path = pathArg(args);
                    if (path.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalArgumentException("path required"));
                    }
                    return capCtx.sshSession().orElseThrow().fileExplorer().readFile(path)
                            .thenApply(bytes -> {
                                JsonObject json = new JsonObject();
                                json.addProperty("path", path);
                                json.addProperty("content", new String(bytes, StandardCharsets.UTF_8));
                                return json;
                            });
                })
                .build());
        context.openTab(displayName(), createView(context));
        context.info("Session demo plugin activated");
    }

    @Override
    public void deactivate() {
        if (context != null) {
            context.capabilityRegistry().unregister("readTextFile");
            context.closeTab();
            context = null;
        }
    }

    @Override public PluginView view() { return this; }

    @Override
    public Node createView(PluginContext context) {
        Label title = new Label("Session-level plugin demo");
        Label session = new Label("Session: "
                + context.sshSession().map(s -> s.sessionId()).orElse("not available"));
        session.setWrapText(true);

        TextArea output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        VBox.setVgrow(output, Priority.ALWAYS);

        Button uptime = new Button("Run uptime");
        uptime.setOnAction(e -> runCommand(context, "uptime", output));

        Button whoami = new Button("Run whoami");
        whoami.setOnAction(e -> runCommand(context, "whoami", output));

        Button storage = new Button("Storage check");
        storage.setOnAction(e -> {
            if (context.storage() == null) {
                output.setText("Plugin storage is not available.");
                return;
            }
            context.storage().put("last.check", Long.toString(System.currentTimeMillis()));
            output.setText("Stored keys: " + context.storage().keys());
            context.showNotification("Session demo storage updated.", NotificationLevel.INFO);
        });

        HBox actions = new HBox(8, uptime, whoami, storage);
        VBox root = new VBox(10, title, session, actions, output);
        root.setPadding(new Insets(12));
        return root;
    }

    private static void runCommand(PluginContext context, String command, TextArea output) {
        context.sshSession().ifPresentOrElse(ssh -> {
            output.setText("Running: " + command);
            ssh.commandExecutor().execute(command).whenComplete((result, error) -> Platform.runLater(() -> {
                if (error != null) {
                    output.setText("Error: " + error.getMessage());
                    context.showNotification("Command failed: " + error.getMessage(), NotificationLevel.ERROR);
                    return;
                }
                String text = result.stdout() == null || result.stdout().isBlank()
                        ? result.stderr()
                        : result.stdout();
                output.setText(text == null ? "" : text);
            }));
        }, () -> output.setText("No SSH session is available."));
    }

    private static String pathArg(com.google.gson.JsonElement args) {
        if (args == null || !args.isJsonObject()) {
            return "";
        }
        JsonObject obj = args.getAsJsonObject();
        return obj.has("path") && !obj.get("path").isJsonNull()
                ? obj.get("path").getAsString()
                : "";
    }
}
