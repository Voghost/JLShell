package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.session.SshSession;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.ui.dialog.PreferencesDialog;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.support.SwingNodeImeBridge;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 终端工作区，含系统信息条和字体设置。
 */
public class TerminalWorkspaceView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(TerminalWorkspaceView.class);

    private final SshSession sshSession;
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final StackPane terminalHost = new StackPane();
    private final List<TerminalViewHandle> handles = new ArrayList<>();

    private AppTheme appTheme;
    private Node primaryNode;

    // System info bar labels
    private Label hostLabel;
    private Label ipLabel;
    private Label osLabel;
    private Label cpuLabel;
    private Label memLabel;
    private Label diskLabel;
    private Timeline infoTimeline;
    private volatile boolean pollingInProgress;

    // Floating card state
    private javafx.stage.Popup floatingPopup;
    private VBox floatingContent;
    private String floatingPendingType;

    public TerminalWorkspaceView(
            SshSession sshSession,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            I18nService i18nService,
            AppTheme appTheme,
            ThemeService themeService
    ) {
        this.sshSession = sshSession;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.i18nService = i18nService;
        this.themeService = themeService;
        this.appTheme = appTheme;

        getStyleClass().add("workspace-panel");
        setTop(buildToolbar());
        setCenter(terminalHost);
    }

    public CompletableFuture<Void> initialize() {
        log.info("Initializing terminal workspace for session {}", sshSession.sessionId());
        terminalHost.getChildren().setAll(new ProgressIndicator());
        return createTerminalNode().thenAccept(node -> FxThread.run(() -> {
            primaryNode = node;
            terminalHost.getChildren().setAll(node);
            log.info("Terminal workspace initialized for session {}", sshSession.sessionId());
            startInfoPolling();
        }));
    }

    public void applyTheme(AppTheme theme) {
        this.appTheme = theme;
        handles.forEach(handle -> handle.updateColorScheme(theme.terminalColorScheme()));
    }

    public CompletableFuture<Void> closeAsync() {
        stopInfoPolling();
        return CompletableFuture.allOf(
                handles.stream()
                        .map(TerminalViewHandle::closeAsync)
                        .toArray(CompletableFuture[]::new)
        );
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        Button fontSettings = iconBtn("/icons/font.svg", i18nService.get("terminal.fontSettings"), this::openFontSettings);

        hostLabel = new Label(sshSession.displayName());
        hostLabel.getStyleClass().add("sysinfo-label");
        ipLabel = new Label();
        ipLabel.getStyleClass().add("sysinfo-label");
        osLabel = new Label();
        osLabel.getStyleClass().add("sysinfo-label");
        cpuLabel = new Label();
        cpuLabel.getStyleClass().add("sysinfo-label");
        memLabel = new Label();
        memLabel.getStyleClass().add("sysinfo-label");
        diskLabel = new Label();
        diskLabel.getStyleClass().add("sysinfo-label");

        HBox ipSection = sysinfoSection(loadSvgShape("/icons/ip.svg", 14), ipLabel, "ip");
        HBox osSection = sysinfoSection(loadSvgShape("/icons/system.svg", 14), osLabel, "os");
        HBox cpuSection = sysinfoSection(loadSvgShape("/icons/cpu.svg", 14), cpuLabel, "cpu");
        HBox memSection = sysinfoSection(loadSvgShape("/icons/memory-solid.svg", 14), memLabel, "mem");
        HBox diskSection = sysinfoSection(loadSvgShape("/icons/folder.svg", 14), diskLabel, "disk");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(4,
                hostLabel, makeSep(),
                ipSection, makeSep(),
                osSection, makeSep(),
                cpuSection, makeSep(),
                memSection, makeSep(),
                diskSection,
                spacer, fontSettings);
        toolbar.getStyleClass().add("toolbar-strip");
        return toolbar;
    }

    private HBox sysinfoSection(Region icon, Label label, String type) {
        if (icon != null) icon.getStyleClass().add("action-bar-icon");
        HBox box = icon != null ? new HBox(2, icon, label) : new HBox(2, label);
        box.getStyleClass().add("sysinfo-section");
        box.setOnMouseClicked(e -> {
            e.consume();
            toggleFloatingCard(box, type);
        });
        return box;
    }

    private static Region makeSep() {
        Region sep = new Region();
        sep.getStyleClass().add("sysinfo-sep");
        return sep;
    }

    // ── Floating card (click to show, click elsewhere to hide) ───────────────

    private void toggleFloatingCard(HBox anchor, String type) {
        // If same type is showing, just hide
        if (floatingPopup != null && floatingPopup.isShowing() && floatingPendingType == type) {
            hideFloatingCard();
            return;
        }
        hideFloatingCard();

        floatingPendingType = type;

        // Loading indicator
        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(20, 20);

        floatingContent = new VBox(4, loading);
        floatingContent.setAlignment(Pos.CENTER);
        floatingContent.setPadding(new Insets(10));
        floatingContent.setPrefWidth(440);
        floatingContent.getStyleClass().add("hover-card");

        floatingPopup = new javafx.stage.Popup();
        floatingPopup.getContent().setAll(floatingContent);
        floatingPopup.setAutoHide(true);
        floatingPopup.setAutoFix(true);

        Point2D pos = anchor.localToScreen(0, anchor.getHeight() + 4);
        floatingPopup.show(anchor, pos.getX(), pos.getY());

        // Fetch and format
        String cmd = buildDetailCmd(type);
        sshSession.execute(new CommandRequest(cmd, java.time.Duration.ofSeconds(10), false, null))
                .thenAccept(output -> FxThread.run(() -> {
                    if (floatingPopup != null && floatingPopup.isShowing() && floatingPendingType == type) {
                        renderFormattedContent(type, output.stdout());
                    }
                }))
                .exceptionally(ex -> {
                    FxThread.run(() -> {
                        if (floatingPopup != null && floatingPopup.isShowing()) {
                            floatingContent.getChildren().setAll(kvRow("Error", ex.getMessage()));
                        }
                    });
                    return null;
                });
    }

    private void hideFloatingCard() {
        if (floatingPopup != null) {
            floatingPopup.hide();
            floatingPopup = null;
        }
        floatingPendingType = null;
    }

    private String buildDetailCmd(String type) {
        switch (type) {
            case "ip":
                return "ip -brief addr show 2>/dev/null || ifconfig 2>/dev/null";
            case "os":
                return "hostnamectl 2>/dev/null || (echo \"Static hostname: $(hostname)\"; "
                        + "echo \"Operating System: $(uname -s) $(uname -r)\"; "
                        + "echo \"Architecture: $(uname -m)\"; "
                        + "echo \"Kernel: $(uname -r)\")";
            case "cpu":
                return "lscpu 2>/dev/null || (echo \"Model name: $(sysctl -n machdep.cpu.brand_string 2>/dev/null)\"; "
                        + "echo \"CPU(s): $(sysctl -n hw.logicalcpu 2>/dev/null)\")";
            case "mem":
                return "free -h 2>/dev/null || (echo \"Total: $(sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1073741824)\" GB\"}')\"; "
                        + "vm_stat 2>/dev/null); "
                        + "echo \"---SWAP---\"; "
                        + "swapon --show 2>/dev/null || echo \"Swap: $(sysctl -n vm.swapusage 2>/dev/null || echo N/A)\"";
            case "disk":
                return "df -h 2>/dev/null || df -k 2>/dev/null";
            default:
                return "echo N/A";
        }
    }

    // ── Formatted content rendering ──────────────────────────────────────────

    private void renderFormattedContent(String type, String raw) {
        floatingContent.getChildren().clear();
        floatingContent.setAlignment(Pos.TOP_LEFT);

        switch (type) {
            case "ip" -> renderIpContent(raw);
            case "os" -> renderOsContent(raw);
            case "cpu" -> renderCpuContent(raw);
            case "mem" -> renderMemContent(raw);
            case "disk" -> renderDiskContent(raw);
            default -> floatingContent.getChildren().add(monospaceBlock(raw));
        }
    }

    private void renderIpContent(String raw) {
        // ip -brief addr show format: "lo               UNKNOWN        127.0.0.1/8"
        // or ifconfig format with inet lines
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("lo") || line.startsWith("Loopback")) continue;

            // Try "ip -brief" format: iface state ip/cidr ...
            String[] parts = line.split("\\s+");
            if (parts.length >= 3 && (parts[1].equals("UP") || parts[2].contains("/"))) {
                String iface = parts[0];
                String ips = String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                floatingContent.getChildren().add(kvRow(iface, ips));
            } else if (line.contains("inet ") || line.contains("inet6 ")) {
                // ifconfig format
                floatingContent.getChildren().add(monospaceLine(line));
            } else {
                floatingContent.getChildren().add(monospaceLine(line));
            }
        }
    }

    private void renderOsContent(String raw) {
        // hostnamectl format: "  Static hostname: xxx"
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                floatingContent.getChildren().add(kvRow(key, val));
            } else {
                floatingContent.getChildren().add(monospaceLine(line));
            }
        }
    }

    private void renderCpuContent(String raw) {
        // lscpu: "Model name:            Intel..."
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                // Only show interesting fields
                if (key.matches("Model name|CPU\\(s\\)|Thread|Core|Socket|Architecture|CPU MHz|CPU max MHz|L[123] cache|Vendor ID|CPU family")) {
                    floatingContent.getChildren().add(kvRow(key, val));
                }
            }
        }
    }

    private void renderMemContent(String raw) {
        // free -h output or vm_stat
        String[] sections = raw.split("---SWAP---");
        String memSection = sections[0].trim();
        String swapSection = sections.length > 1 ? sections[1].trim() : "";

        // Parse "free -h" table format
        String[] lines = memSection.split("\n");
        if (lines.length >= 2 && lines[0].trim().startsWith("total")) {
            // Headers: total used free shared buff/cache available
            String[] headers = lines[0].trim().split("\\s+");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] vals = line.split("\\s+");
                String rowName = vals[0]; // Mem: / Swap:
                for (int j = 1; j < vals.length && j < headers.length; j++) {
                    floatingContent.getChildren().add(kvRow(
                            rowName + " " + headers[j - 1], vals[j]));
                }
                // Add usage bar for Mem row
                if (rowName.startsWith("Mem") && vals.length >= 3) {
                    try {
                        String used = vals[1].replaceAll("[^0-9.]", "");
                        String total = vals[1].replaceAll("[^0-9.]", "");
                        // rough percentage from used/total
                        if (vals.length >= 2) {
                            floatingContent.getChildren().add(usageBar(vals[1], vals[2]));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } else {
            // vm_stat or other format — show as key-value
            for (String line : memSection.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    floatingContent.getChildren().add(kvRow(
                            line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim()));
                } else {
                    floatingContent.getChildren().add(monospaceLine(line));
                }
            }
        }

        // Swap section
        if (!swapSection.isEmpty()) {
            floatingContent.getChildren().add(sectionHeader("Swap"));
            for (String line : swapSection.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    floatingContent.getChildren().add(kvRow(
                            line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim()));
                } else {
                    floatingContent.getChildren().add(monospaceLine(line));
                }
            }
        }
    }

    private void renderDiskContent(String raw) {
        // df -h table: Filesystem Size Used Avail Use% Mounted on
        String[] lines = raw.split("\n");
        if (lines.length < 2) {
            floatingContent.getChildren().add(monospaceBlock(raw));
            return;
        }

        // Parse header to find column indices
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;

            String fs = parts[0];
            String size = parts[1];
            String used = parts[2];
            String avail = parts[3];
            String usePct = parts.length > 4 ? parts[4] : "";
            String mount = parts.length > 5 ? parts[5] : "";

            // Skip tmpfs, devtmpfs, squashfs
            if (fs.startsWith("tmpfs") || fs.startsWith("devtmpfs") || fs.startsWith("squashfs")) continue;

            // Mount point as header, then details
            floatingContent.getChildren().add(sectionHeader(mount.isEmpty() ? fs : mount));
            floatingContent.getChildren().add(kvRow("Device", fs));
            floatingContent.getChildren().add(kvRow("Size", size));
            floatingContent.getChildren().add(kvRow("Used", used + " / " + size + " (" + usePct + ")"));
            floatingContent.getChildren().add(kvRow("Avail", avail));

            // Usage bar
            if (!usePct.isEmpty() && usePct.endsWith("%")) {
                try {
                    int pct = Integer.parseInt(usePct.replace("%", ""));
                    floatingContent.getChildren().add(usageBar(pct));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ── Card UI components ───────────────────────────────────────────────────

    private HBox kvRow(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("card-key");
        Label valLabel = new Label(value);
        valLabel.getStyleClass().add("card-val");
        HBox row = new HBox(8, keyLabel, valLabel);
        row.getStyleClass().add("card-row");
        return row;
    }

    private Label sectionHeader(String text) {
        Label header = new Label(text);
        header.getStyleClass().add("card-section");
        return header;
    }

    private Label monospaceLine(String text) {
        Label line = new Label(text);
        line.getStyleClass().add("card-mono");
        return line;
    }

    private VBox monospaceBlock(String text) {
        VBox box = new VBox(1);
        for (String line : text.split("\n")) {
            box.getChildren().add(monospaceLine(line));
        }
        return box;
    }

    /** Usage bar with percentage label: "used / free" */
    private HBox usageBar(String used, String free) {
        Label label = new Label(used + " used / " + free + " free");
        label.getStyleClass().add("card-val");
        HBox row = new HBox(label);
        row.getStyleClass().add("card-row");
        return row;
    }

    /** Usage bar with integer percentage */
    private Node usageBar(int pct) {
        Region bar = new Region();
        bar.getStyleClass().add("card-bar-fill");
        bar.setPrefWidth(Math.max(1, pct * 2.8));  // max width ~280px
        bar.setMinHeight(6);
        bar.setPrefHeight(6);
        bar.setMaxHeight(6);

        // Color based on usage
        if (pct >= 90) {
            bar.setStyle("-fx-background-color:#ef4444;-fx-background-radius:3;");
        } else if (pct >= 70) {
            bar.setStyle("-fx-background-color:#f59e0b;-fx-background-radius:3;");
        } else {
            bar.setStyle("-fx-background-color:#22c55e;-fx-background-radius:3;");
        }

        Region track = new Region();
        track.getStyleClass().add("card-bar-track");
        track.setPrefWidth(280);
        track.setMinHeight(6);
        track.setPrefHeight(6);
        track.setMaxHeight(6);

        StackPane stack = new StackPane(track, bar);
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        stack.setMaxWidth(280);

        Label pctLabel = new Label(pct + "%");
        pctLabel.getStyleClass().add("card-val");

        HBox row = new HBox(8, stack, pctLabel);
        row.getStyleClass().add("card-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ── SVG icon loading ─────────────────────────────────────────────────────

    private Region loadSvgShape(String resourcePath, double size) {
        try (var is = TerminalWorkspaceView.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            String content = new String(is.readAllBytes());
            String pathData = extractSvgPath(content);
            if (pathData == null) return null;
            Region region = new Region();
            region.setMinSize(size, size);
            region.setMaxSize(size, size);
            region.setPrefSize(size, size);
            SVGPath svg = new SVGPath();
            svg.setContent(pathData);
            region.setShape(svg);
            region.setStyle("-fx-scale-shape:true;");
            region.getStyleClass().add("action-bar-icon");
            return region;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSvgPath(String svgContent) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < svgContent.length()) {
            int start = svgContent.indexOf("d=\"", idx);
            if (start == -1) break;
            if (start > 0 && Character.isLetterOrDigit(svgContent.charAt(start - 1))) {
                idx = start + 3;
                continue;
            }
            start += 3;
            int end = svgContent.indexOf("\"", start);
            if (end == -1) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(svgContent.substring(start, end));
            idx = end + 1;
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private Button iconBtn(String iconResourcePath, String tooltip, Runnable action) {
        Region icon = loadSvgShape(iconResourcePath, 14);
        Button btn = new Button();
        if (icon != null) {
            btn.setGraphic(icon);
        }
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        btn.getStyleClass().add("icon-btn");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private void openFontSettings() {
        Window owner = getScene() != null ? getScene().getWindow() : null;
        Stage stage = owner instanceof Stage ? (Stage) owner : null;
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService);
        FontProfile profile = fontProfileService.activeProfile();
        handles.forEach(h -> h.updateFontProfile(profile));
    }

    // ── System info polling ──────────────────────────────────────────────────

    private void startInfoPolling() {
        pollSystemInfo();
        infoTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), e -> pollSystemInfo()));
        infoTimeline.setCycleCount(Animation.INDEFINITE);
        infoTimeline.play();
    }

    private void stopInfoPolling() {
        if (infoTimeline != null) {
            infoTimeline.stop();
            infoTimeline = null;
        }
    }

    private void pollSystemInfo() {
        // Skip if previous poll is still running (avoid command queue buildup)
        if (pollingInProgress) return;
        pollingInProgress = true;

        String script = loadScript("/scripts/sysinfo.sh");
        if (script == null) {
            log.warn("sysinfo.sh not found, polling disabled");
            pollingInProgress = false;
            return;
        }
        // Pass script via heredoc — single-quoted EOF prevents any shell expansion
        String cmd = "sh <<'JLSHELL_EOF'\n" + script + "\nJLSHELL_EOF";
        sshSession.execute(new CommandRequest(cmd, java.time.Duration.ofSeconds(8), false, null))
                .thenAccept(output -> FxThread.run(() -> {
                    log.debug("sysinfo poll result: {}", output.stdout().trim());
                    parseInfoOutput(output.stdout());
                }))
                .exceptionally(ex -> {
                    log.debug("System info poll failed: {}", ex.getMessage());
                    return null;
                })
                .whenComplete((r, ex) -> pollingInProgress = false);
    }

    private static String loadScript(String resourcePath) {
        try (var is = TerminalWorkspaceView.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private void parseInfoOutput(String output) {
        try {
            String[] parts = output.trim().split("\\|\\|\\|");
            String osArch = parts.length > 0 ? parts[0].trim() : "";
            String cpuVal = parts.length > 1 ? parts[1].trim() : "";
            String memVal = parts.length > 2 ? parts[2].trim() : "";
            String diskVal = parts.length > 3 ? parts[3].trim() : "";
            String ipVal = parts.length > 4 ? parts[4].trim() : "";

            if (!osArch.isEmpty()) {
                osLabel.setText(osArch);
            }

            // CPU: if top returned a percentage (small number 0-100), show as %;
            // if nproc returned core count, show as "N CPU"
            if (!cpuVal.isEmpty() && !cpuVal.equals("?")) {
                try {
                    int v = Integer.parseInt(cpuVal);
                    cpuLabel.setText(v <= 100 ? v + "% CPU" : v + " CPU");
                } catch (NumberFormatException e) {
                    try {
                        double v = Double.parseDouble(cpuVal);
                        cpuLabel.setText((int) v + "% CPU");
                    } catch (NumberFormatException e2) {
                        cpuLabel.setText(cpuVal + " CPU");
                    }
                }
            } else {
                cpuLabel.setText("--");
            }

            // MEM percentage
            if (!memVal.isEmpty()) {
                try {
                    int v = Integer.parseInt(memVal);
                    memLabel.setText(v + "% MEM");
                } catch (NumberFormatException e) {
                    try {
                        double v = Double.parseDouble(memVal);
                        memLabel.setText((int) v + "% MEM");
                    } catch (NumberFormatException e2) {
                        memLabel.setText("--");
                    }
                }
            } else {
                memLabel.setText("--");
            }

            // Disk percentage
            if (!diskVal.isEmpty()) {
                diskLabel.setText(diskVal + "% DISK");
            } else {
                diskLabel.setText("--");
            }

            // IP
            if (!ipVal.isEmpty()) {
                ipLabel.setText(ipVal);
            } else {
                ipLabel.setText("--");
            }
        } catch (Exception e) {
            log.debug("Failed to parse system info: {}", e.getMessage());
        }
    }

    // ── Terminal node creation ────────────────────────────────────────────────

    private CompletableFuture<Node> createTerminalNode() {
        FontProfile fontProfile = fontProfileService.activeProfile();
        TerminalViewRequest request = new TerminalViewRequest(
                sshSession.displayName(),
                new ShellRequest("xterm-256color", new TerminalSize(120, 40, 0, 0), null),
                fontProfile,
                appTheme.terminalColorScheme()
        );
        return terminalViewFactory.createTerminalView(sshSession, request)
                .thenCompose(handle -> {
                    handles.add(handle);
                    return FxThread.supplyAsync(() -> createEmbeddedTerminalNode(handle));
                });
    }

    private Node createEmbeddedTerminalNode(TerminalViewHandle handle) {
        log.info("Attaching SwingNode terminal component for session {}", sshSession.sessionId());
        javax.swing.JComponent component = handle.component();
        SwingNode swingNode = new SwingNode();
        swingNode.setFocusTraversable(true);
        swingNode.setContent(component);
        swingNode.focusedProperty().addListener((obs, oldFocused, focused) -> {
            if (focused) {
                handle.requestFocus();
            }
        });
        swingNode.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> handle.requestFocus());
        HBox.setHgrow(swingNode, Priority.ALWAYS);
        SwingNodeImeBridge.attach(swingNode, handle);
        FxThread.run(handle::requestFocus);
        return swingNode;
    }
}
