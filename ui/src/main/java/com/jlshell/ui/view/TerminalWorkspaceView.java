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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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

    // System info bar
    private Label hostLabel;
    private Label osLabel;
    private Label cpuLabel;
    private Label memLabel;
    private Timeline infoTimeline;

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

    private static final String ICON_FONT = "M5 4h14v3h-1V6h-5v13h2v1H9v-1h2V6H6v1H5z";

    private HBox buildToolbar() {
        Button fontSettings = iconBtn("/icons/font.svg", i18nService.get("terminal.fontSettings"), this::openFontSettings);

        // System info labels
        hostLabel = new Label(sshSession.displayName());
        hostLabel.getStyleClass().add("sysinfo-label");
        osLabel = new Label();
        osLabel.getStyleClass().add("sysinfo-label");
        cpuLabel = new Label();
        cpuLabel.getStyleClass().add("sysinfo-label");
        memLabel = new Label();
        memLabel.getStyleClass().add("sysinfo-label");

        Label separator1 = new Label("│");
        separator1.getStyleClass().add("sysinfo-sep");
        Label separator2 = new Label("│");
        separator2.getStyleClass().add("sysinfo-sep");
        Label separator3 = new Label("│");
        separator3.getStyleClass().add("sysinfo-sep");

        // CPU and MEM icons
        Region cpuIcon = loadSvgShape("/icons/cpu.svg", 14);
        Region memIcon = loadSvgShape("/icons/memory-solid.svg", 14);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(4,
                hostLabel, separator1, osLabel, separator2,
                cpuIcon != null ? cpuIcon : new Region(), cpuLabel,
                separator3,
                memIcon != null ? memIcon : new Region(), memLabel,
                spacer, fontSettings);
        toolbar.getStyleClass().add("toolbar-strip");
        return toolbar;
    }

    private Region loadSvgShape(String resourcePath, double size) {
        var url = TerminalWorkspaceView.class.getResource(resourcePath);
        if (url == null) return null;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(url.toURI())));
            int start = content.indexOf("d=\"");
            if (start == -1) return null;
            start += 3;
            int end = content.indexOf("\"", start);
            if (end == -1) return null;
            String pathData = content.substring(start, end);
            Region region = new Region();
            region.setStyle(String.format(
                    "-fx-min-width:%.0fpx;-fx-min-height:%.0fpx;" +
                    "-fx-max-width:%.0fpx;-fx-max-height:%.0fpx;" +
                    "-fx-pref-width:%.0fpx;-fx-pref-height:%.0fpx;" +
                    "-fx-shape:\"%s\";-fx-scale-shape:true;",
                    size, size, size, size, size, size, pathData));
            region.getStyleClass().add("action-bar-icon");
            return region;
        } catch (Exception e) {
            return null;
        }
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
        javafx.stage.Stage stage = owner instanceof javafx.stage.Stage ? (javafx.stage.Stage) owner : null;
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService);
        FontProfile profile = fontProfileService.activeProfile();
        handles.forEach(h -> h.updateFontProfile(profile));
    }

    // ── System info polling ──────────────────────────────────────────────────

    private void startInfoPolling() {
        // Fetch initial info immediately
        pollSystemInfo();
        // Then poll every 5 seconds
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
        // Single command: outputs OS, arch, CPU cores, and memory info
        String cmd = "echo \"$(uname -s)|||$(uname -m)|||$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo ?)|||$(cat /proc/meminfo 2>/dev/null | head -3 || vm_stat 2>/dev/null | head -10)\"";
        sshSession.execute(new CommandRequest(cmd, java.time.Duration.ofSeconds(5), false, null))
                .thenAccept(output -> FxThread.run(() -> parseInfoOutput(output.stdout())))
                .exceptionally(ex -> {
                    log.debug("System info poll failed: {}", ex.getMessage());
                    FxThread.run(() -> {
                        cpuLabel.setText("--");
                        memLabel.setText("--");
                    });
                    return null;
                });
    }

    private void parseInfoOutput(String output) {
        try {
            String[] parts = output.trim().split("\\|\\|\\|");
            String osName = parts.length > 0 ? parts[0].trim() : "";
            String arch = parts.length > 1 ? parts[1].trim() : "";
            String cores = parts.length > 2 ? parts[2].trim() : "?";
            String memInfo = parts.length > 3 ? parts[3].trim() : "";

            if (!osName.isEmpty()) {
                osLabel.setText(osName + " " + arch);
            }
            cpuLabel.setText(cores.equals("?") ? "--" : cores + " CPU");

            // Parse memory
            if (memInfo.contains("MemTotal:")) {
                // Linux /proc/meminfo format
                long totalKb = 0, availableKb = 0;
                for (String line : memInfo.split("\n")) {
                    if (line.startsWith("MemTotal:")) totalKb = extractKb(line);
                    else if (line.startsWith("MemAvailable:")) availableKb = extractKb(line);
                }
                if (totalKb > 0) {
                    int pct = (int) ((1 - (double) availableKb / totalKb) * 100);
                    memLabel.setText(pct + "% MEM");
                } else {
                    memLabel.setText("--");
                }
            } else if (memInfo.contains("Pages free:") || memInfo.contains("page size")) {
                // macOS vm_stat
                long free = 0, active = 0, wired = 0, inactive = 0;
                for (String line : memInfo.split("\n")) {
                    if (line.contains("Pages free:")) free = parseVmNum(line);
                    else if (line.contains("Pages active:")) active = parseVmNum(line);
                    else if (line.contains("Pages inactive:")) inactive = parseVmNum(line);
                    else if (line.contains("Pages wired")) wired = parseVmNum(line);
                }
                long pageSize = 4096;
                long total = (free + active + inactive + wired) * pageSize;
                long used = (active + wired) * pageSize;
                if (total > 0) {
                    int pct = (int) ((double) used / total * 100);
                    memLabel.setText(pct + "% MEM");
                } else {
                    memLabel.setText("--");
                }
            } else {
                memLabel.setText("--");
            }
        } catch (Exception e) {
            log.debug("Failed to parse system info: {}", e.getMessage());
            cpuLabel.setText("--");
            memLabel.setText("--");
        }
    }

    private long extractKb(String line) {
        try {
            return Long.parseLong(line.replaceAll("[^0-9]", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseVmNum(String line) {
        try {
            return Long.parseLong(line.replaceAll("[^\\d]", "").trim());
        } catch (NumberFormatException e) {
            return 0;
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
