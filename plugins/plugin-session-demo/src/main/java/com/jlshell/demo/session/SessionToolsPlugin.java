package com.jlshell.demo.session;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.rpc.Capability;

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
    public Supplier<JComponent> createView(PluginContext context) {
        return () -> createPanel(context);
    }

    private JComponent createPanel(PluginContext context) {
        JLabel title = new JLabel("Session-level plugin demo");
        JLabel session = new JLabel("Session: "
                + context.sshSession().map(s -> s.sessionId()).orElse("not available"));


        JTextArea output = new JTextArea();
        output.setEditable(false);
        output.setLineWrap(true);


        JButton uptime = new JButton("Run uptime");
        uptime.addActionListener(e -> runCommand(context, "uptime", output));

        JButton whoami = new JButton("Run whoami");
        whoami.addActionListener(e -> runCommand(context, "whoami", output));

        JButton storage = new JButton("Storage check");
        storage.addActionListener(e -> {
            if (context.storage() == null) {
                output.setText("Plugin storage is not available.");
                return;
            }
            context.storage().put("last.check", Long.toString(System.currentTimeMillis()));
            output.setText("Stored keys: " + context.storage().keys());
            context.showNotification("Session demo storage updated.", NotificationLevel.INFO);
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(uptime); actions.add(whoami); actions.add(storage);
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 8));
        header.add(title); header.add(session); header.add(actions);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(header, BorderLayout.NORTH);
        root.add(new JScrollPane(output));
        return root;
    }

    private static void runCommand(PluginContext context, String command, JTextArea output) {
        context.sshSession().ifPresentOrElse(ssh -> {
            output.setText("Running: " + command);
            ssh.commandExecutor().execute(command).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
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
