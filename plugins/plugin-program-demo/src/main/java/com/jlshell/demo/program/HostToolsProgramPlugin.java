package com.jlshell.demo.program;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.program.api.ProgramApiContext;
import com.jlshell.program.api.ProgramApiProvider;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * Program-level demo plugin. It has no SSH session and registers global capabilities.
 */
public class HostToolsProgramPlugin implements JlShellProgramPlugin, ProgramApiProvider {

    private ProgramPluginContext context;

    @Override public String id() { return "com.jlshell.demo.program-host-tools"; }
    @Override public String displayName() { return "Program Host Tools Demo"; }
    @Override public String version() { return "0.1.0"; }
    @Override public String author() { return "JLShell"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
    @Override public String description() {
        return "Demonstrates program-level settings, storage, and global API capabilities.";
    }

    @Override
    public void activate(ProgramPluginContext context) {
        this.context = context;
        context.capabilityRegistry().register(Capability.builder("hostInfo")
                .description("Return basic information about the running JLShell host.")
                .requiresSession(false)
                .handler((args, capCtx) -> CompletableFuture.completedFuture(hostInfo()))
                .build());
        context.capabilityRegistry().register(Capability.builder("echo")
                .description("Echo the input payload and add program plugin runtime metadata.")
                .requiresSession(false)
                .handler((args, capCtx) -> CompletableFuture.completedFuture(echo(args)))
                .build());
        context.info("Program demo plugin activated");
    }

    /**
     * 由 ProgramPluginManager 发现本插件后调用，使 demo 同时演示外部 JSON-RPC SPI。
     */
    @Override
    public void activate(ProgramApiContext context) {
        context.registry().register("demo.host.info",
                params -> CompletableFuture.completedFuture(hostInfo()));
        context.registry().register("demo.echo",
                params -> CompletableFuture.completedFuture(echo(params)));
    }

    @Override
    public void deactivate() {
        if (context != null) {
            context.capabilityRegistry().unregister("hostInfo");
            context.capabilityRegistry().unregister("echo");
            context.info("Program demo plugin deactivated");
            context = null;
        }
    }

    @Override
    public Node settingsView(ProgramPluginContext context) {
        TextArea note = new TextArea(context.storage() == null
                ? ""
                : context.storage().get("demo.note", ""));
        note.setPromptText("Stored in this plugin's private storage namespace.");
        note.setPrefRowCount(4);
        note.setWrapText(true);

        Label runtime = new Label(runtimeText(context));
        runtime.setWrapText(true);

        Button save = new Button("Save note");
        save.setOnAction(e -> {
            if (context.storage() != null) {
                context.storage().put("demo.note", note.getText() == null ? "" : note.getText());
            }
            context.showNotification("Program demo settings saved.", NotificationLevel.INFO);
        });

        VBox root = new VBox(8,
                new Label("Program-level plugin demo"),
                runtime,
                note,
                save);
        root.setPadding(new Insets(8));
        return root;
    }

    private JsonObject hostInfo() {
        JsonObject json = new JsonObject();
        json.addProperty("pluginId", id());
        json.addProperty("pluginVersion", version());
        json.addProperty("scope", "PROGRAM");
        json.addProperty("theme", context == null ? "" : context.themeName());
        json.addProperty("locale", context == null ? "" : context.locale().toLanguageTag());
        json.addProperty("javaVersion", System.getProperty("java.version"));
        json.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        json.addProperty("timestamp", Instant.now().toString());
        return json;
    }

    private JsonObject echo(JsonElement args) {
        JsonObject json = new JsonObject();
        json.add("input", args == null ? com.google.gson.JsonNull.INSTANCE : args);
        json.addProperty("handledBy", id());
        json.addProperty("requiresSession", false);
        json.addProperty("timestamp", Instant.now().toString());
        return json;
    }

    private static String runtimeText(ProgramPluginContext context) {
        return "Theme: " + context.themeName()
                + "\nLocale: " + context.locale().toLanguageTag()
                + "\nCapabilities: " + context.capabilityRegistry().specs().size();
    }
}
