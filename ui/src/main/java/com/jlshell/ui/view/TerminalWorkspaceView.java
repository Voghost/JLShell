package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.session.SshSession;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.DefaultTerminalViewHandle;
import com.jlshell.terminal.support.ShellTtyConnector;
import com.jlshell.ui.config.JlshellDefaults;
import com.jlshell.ui.dialog.PreferencesDialog;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.support.SwingNodeImeBridge;
import com.jlshell.ui.theme.ThemeService;
import javafx.beans.value.ChangeListener;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
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
    private static final String TERMINAL_SWING_NODE_STYLE_CLASS = "terminal-swing-node";

    private final SshSession sshSession;
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final java.util.function.Consumer<String> sessionDisconnectListener;
    private final StackPane terminalHost = new StackPane();
    private final List<TerminalViewHandle> handles = new ArrayList<>();
    private final List<ChangeListener<String>> cwdListeners = new ArrayList<>();

    private Node primaryNode;
    private ChangeListener<Boolean> primaryFocusListener;
    private EventHandler<MouseEvent> primaryMouseClickHandler;

    /**
     * 终端当前工作目录的可观察属性（来自主终端的 OSC 7 追踪）。
     * 持久属性：不依赖 handles 是否已创建，监听器绑定一次即可。
     * 当终端 handle 创建后，自动桥接到 handle 的 cwdProperty。
     */
    private final javafx.beans.property.SimpleStringProperty cwdProperty = new javafx.beans.property.SimpleStringProperty("");

    public javafx.beans.property.StringProperty cwdProperty() {
        return cwdProperty;
    }

    // 会话底部状态栏
    private StackPane sessionStatusArea;
    private HBox sessionStatusBar;
    private Button statusBarRevealButton;
    private Label ipValueLabel;
    private Label osValueLabel;
    private Label cpuValueLabel;
    private Label memValueLabel;
    private Label diskValueLabel;
    private Label serverTimeLabel;
    private Timeline statusRefreshTimeline;
    private Timeline serverClockTimeline;
    private boolean statusRefreshInFlight;
    private long statusSamplingGeneration;
    private long previousRxBytes = -1;
    private long previousTxBytes = -1;
    private long previousCpuTotal = -1;
    private long previousCpuIdle = -1;
    private long previousSampleNanos = -1;
    private String currentDownloadRate = "--";
    private String currentUploadRate = "--";
    private String currentCpuUsage = "--";
    private int currentCpuPercent = -1;
    private long remoteClockEpochSeconds;
    private long remoteClockSampleNanos = -1;
    private String remoteClockZone = "--";
    private String remoteClockOffset = "+0000";
    private StatusSummary lastStatusSummary;
    private final javafx.beans.property.ReadOnlyObjectWrapper<com.jlshell.ui.model.ServerMonitorSnapshot> monitorSnapshot =
            new javafx.beans.property.ReadOnlyObjectWrapper<>();
    private boolean nativeMonitor;
    private long monitorSample;
    private double monitorNetworkRate;

    public int monitorWarningThreshold() { return warningThreshold; }
    public int monitorDangerThreshold() { return dangerThreshold; }

    public javafx.beans.property.ReadOnlyObjectProperty<com.jlshell.ui.model.ServerMonitorSnapshot> monitorSnapshotProperty() {
        return monitorSnapshot.getReadOnlyProperty();
    }

    /** Reuses the existing read-only SSH detail commands; caller must dispatch from the FX thread. */
    public java.util.concurrent.CompletableFuture<String> queryMonitorDetail(String type) {
        if (disconnected) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Disconnected"));
        if (!java.util.Set.of("cpu", "mem", "ip", "os", "disk").contains(type))
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Unknown metric"));
        return sshSession.execute(new CommandRequest(buildDetailCmd(type), java.time.Duration.ofSeconds(10), false, null))
                .thenApply(output -> {
                    if (output.exitCode() != null && output.exitCode() != 0 && (output.stdout() == null || output.stdout().isBlank()))
                        throw new IllegalStateException(output.stderr());
                    return output.stdout() == null ? "" : output.stdout();
                });
    }

    private void publishMonitorSnapshot(String failure) {
        StatusSummary summary = lastStatusSummary;
        if (summary == null) {
            var missing = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource("—", "—", -1, -1);
            monitorSnapshot.set(new com.jlshell.ui.model.ServerMonitorSnapshot(0, "—", "—", "—", "—", "—", "—",
                    missing, missing, missing, missing, failure));
            return;
        }
        var cpu = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(currentCpuUsage,
                summary.cores() + " cores · Load " + formatDecimal(summary.load()), currentCpuPercent, currentCpuPercent);
        int memory = usagePercent(summary.memUsed(), summary.memTotal());
        var mem = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(memory < 0 ? "--" : memory + "%",
                formatBytes(summary.memUsed()) + " / " + formatBytes(summary.memTotal()), memory, memory);
        var network = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource("↓ " + currentDownloadRate,
                "↑ " + currentUploadRate, monitorNetworkRate, -1);
        var disk = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(summary.diskPercent() < 0 ? "--" : summary.diskPercent() + "%",
                formatBytes(summary.diskUsed()) + " / " + formatBytes(summary.diskTotal()), summary.diskPercent(), summary.diskPercent());
        monitorSnapshot.set(new com.jlshell.ui.model.ServerMonitorSnapshot(monitorSample, summary.host(), summary.os(), summary.kernel(),
                summary.ip(), summary.iface(), serverTimeLabel.getText(), cpu, mem, network, disk, failure));
    }

    // Floating card state
    private javafx.stage.Popup floatingPopup;
    private VBox floatingContent;
    private ScrollPane floatingScroll;
    private String floatingPendingType;
    private HBox floatingAnchor;
    private final Map<String, Label> floatingLiveLabels = new HashMap<>();
    private final Map<String, LiveUsageBar> floatingUsageBars = new HashMap<>();
    private VBox floatingProcessRows;
    private boolean floatingProcessRefreshInFlight;
    private long floatingProcessGeneration;

    public static final String STATUS_BAR_VISIBLE_KEY = "ui.terminal.statusBar.visible";
    private static final double NETWORK_STATUS_WIDTH = 165;
    private static final double OS_STATUS_WIDTH = 220;
    private static final double CPU_STATUS_WIDTH = 125;
    private static final double MEMORY_STATUS_WIDTH = 175;
    private static final double DISK_STATUS_WIDTH = 180;
    private static final double FLOATING_CARD_WIDTH = 400;
    private static final double FLOATING_CARD_BOTTOM_GAP = 2;
    private static final java.time.Duration STATUS_COMMAND_TIMEOUT = java.time.Duration.ofSeconds(8);
    private final int warningThreshold;
    private final int dangerThreshold;

    /** 断连提示覆盖层：显示断连原因 + 重连按钮 */
    private StackPane disconnectOverlay;
    private Label disconnectLabel;
    private Button reconnectBtn;
    private boolean disconnected = false;
    /** 重连回调，由 SessionWorkspaceTab 设置 */
    private Runnable onReconnect;
    private com.jlshell.ui.swing.NativeTerminalHost nativeTerminalHost;

    public void setNativeTerminalHost(com.jlshell.ui.swing.NativeTerminalHost host) {
        nativeTerminalHost = host;
    }

    /** 只移动界面节点，原有插件按钮、状态采样和事件处理仍由本视图维护。 */

    public Node takeNativeStatusBar() {
        nativeMonitor = true;
        Node node = getBottom();
        setBottom(null);
        return node;
    }

    public TerminalWorkspaceView(
            SshSession sshSession,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            I18nService i18nService,
            ThemeService themeService
    ) {
        this.sshSession = sshSession;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.i18nService = i18nService;
        this.themeService = themeService;
        ResourceThresholds thresholds = ResourceThresholds.of(
                JlshellDefaults.statusWarningPercent(),
                JlshellDefaults.statusDangerPercent()
        );
        this.warningThreshold = thresholds.warning();
        this.dangerThreshold = thresholds.danger();
        this.sessionDisconnectListener = reason -> {
            log.warn("[Terminal] SSH disconnect listener fired for session {}, message={}",
                    sshSession.sessionId(), reason);
            FxThread.run(() -> onTerminalDisconnected(ShellTtyConnector.DisconnectReason.IO_ERROR));
        };
        this.sshSession.addDisconnectListener(sessionDisconnectListener);

        getStyleClass().add("workspace-panel");
        setCenter(terminalHost);
        setBottom(buildSessionStatusArea());
        setStatusBarVisible(Boolean.parseBoolean(appSettingsService.get(STATUS_BAR_VISIBLE_KEY, "true")));
    }

    public CompletableFuture<Void> initialize() {
        log.info("Initializing terminal workspace for session {}", sshSession.sessionId());
        terminalHost.getChildren().setAll(new ProgressIndicator());
        return createTerminalNode().thenAccept(node -> FxThread.run(() -> {
            primaryNode = node;
            terminalHost.getChildren().setAll(node);
            startStatusSampling();
            log.info("Terminal workspace initialized for session {}", sshSession.sessionId());
        }));
    }


    /** 控制会话底部资源状态栏的显隐，并持久化用户选择。 */
    public void setStatusBarVisible(boolean visible) {
        if (sessionStatusBar != null) {
            sessionStatusBar.setManaged(visible);
            sessionStatusBar.setVisible(visible);
        }
        if (statusBarRevealButton != null) {
            statusBarRevealButton.setManaged(!visible);
            statusBarRevealButton.setVisible(!visible);
        }
        appSettingsService.set(STATUS_BAR_VISIBLE_KEY, String.valueOf(visible));
        if (visible) {
            startStatusSampling();
        } else {
            stopStatusSampling();
            hideFloatingCard();
        }
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        handles.forEach(handle -> handle.updateColorScheme(scheme));
    }


    public CompletableFuture<Void> closeAsync() {
        hideFloatingCard();
        stopStatusSampling();
        sshSession.removeDisconnectListener(sessionDisconnectListener);
        detachPrimarySwingNode();
        for (int i = 0; i < handles.size(); i++) {
            TerminalViewHandle handle = handles.get(i);
            if (i < cwdListeners.size()) {
                handle.cwdProperty().removeListener(cwdListeners.get(i));
            }
            if (handle instanceof DefaultTerminalViewHandle dvh) {
                dvh.setOnDisconnected(null);
            }
        }
        return CompletableFuture.allOf(
                new ArrayList<>(handles).stream()
                        .map(TerminalViewHandle::closeAsync)
                        .toArray(CompletableFuture[]::new)
        ).whenComplete((unused, throwable) -> FxThread.run(this::disposeUiReferences));
    }

    private void disposeUiReferences() {
        hideFloatingCard();
        detachPrimarySwingNode();
        terminalHost.getChildren().clear();
        handles.clear();
        cwdListeners.clear();
        primaryNode = null;
        primaryFocusListener = null;
        primaryMouseClickHandler = null;
        disconnectOverlay = null;
        disconnectLabel = null;
        reconnectBtn = null;
        sessionStatusBar = null;
        sessionStatusArea = null;
        statusBarRevealButton = null;
        ipValueLabel = null;
        osValueLabel = null;
        cpuValueLabel = null;
        memValueLabel = null;
        diskValueLabel = null;
        serverTimeLabel = null;
        statusRefreshTimeline = null;
        serverClockTimeline = null;
        floatingUsageBars.clear();
        floatingProcessRows = null;
        onReconnect = null;
    }

    private void detachPrimarySwingNode() {
        if (primaryNode instanceof SwingNode swingNode) {
            if (primaryFocusListener != null) {
                swingNode.focusedProperty().removeListener(primaryFocusListener);
            }
            if (primaryMouseClickHandler != null) {
                swingNode.removeEventHandler(MouseEvent.MOUSE_CLICKED, primaryMouseClickHandler);
            }
            SwingNodeImeBridge.detach(swingNode);
            swingNode.setContent(null);
        }
    }

    /** 设置重连回调，由 SessionWorkspaceTab 在创建时注入。 */
    public void setOnReconnect(Runnable onReconnect) {
        this.onReconnect = onReconnect;
    }

    /**
     * 终端连接断开回调，在 JavaFX 应用线程执行。
     * 在终端上方覆盖显示断连原因和重连按钮。
     */
    private void onTerminalDisconnected(ShellTtyConnector.DisconnectReason reason) {
        String reasonText = switch (reason) {
            case REMOTE_CLOSED -> i18nService.get("terminal.disconnected.remoteClosed");
            case IO_ERROR -> i18nService.get("terminal.disconnected.ioError");
            case USER_CLOSE -> null; // 用户主动关闭不显示提示
        };
        if (reasonText == null) {
            log.debug("[Terminal] Ignoring user-initiated disconnect for session {}", sshSession.sessionId());
            return;
        }
        if (disconnected) {
            log.debug("[Terminal] Duplicate disconnect ignored for session {}, reason={}",
                    sshSession.sessionId(), reason);
            return;
        }

        long startedAt = System.nanoTime();
        disconnected = true;
        if (nativeTerminalHost != null) {
            nativeTerminalHost.disconnected(reasonText, () -> {
                hideDisconnectOverlay();
                if (onReconnect != null) onReconnect.run();
            });
        }
        stopStatusSampling();
        log.warn("[Terminal] Session {} disconnected, reason={}, handles={}, primaryNode={}",
                sshSession.sessionId(), reason, handles.size(),
                primaryNode == null ? "null" : primaryNode.getClass().getSimpleName());

        // SwingNode 直接渲染到窗口 native 层，普通 JavaFX 节点无法覆盖它。
        // 断连时从场景里移除并释放 Swing 内容，让覆盖层立刻可见。
        if (primaryNode != null) {
            primaryNode.setVisible(false);
            primaryNode.setManaged(false);
        }
        detachPrimarySwingNode();
        closeTerminalHandlesAfterDisconnect(reason);

        // 创建断连提示覆盖层
        disconnectLabel = new Label(reasonText);
        disconnectLabel.getStyleClass().add("disconnect-reason");

        reconnectBtn = new Button(i18nService.get("terminal.disconnected.reconnect"));
        reconnectBtn.getStyleClass().add("disconnect-reconnect-btn");
        reconnectBtn.setOnAction(e -> {
            log.info("[Terminal] Reconnect button clicked for session {}", sshSession.sessionId());
            hideDisconnectOverlay();
            if (onReconnect != null) {
                onReconnect.run();
            }
        });

        VBox card = new VBox(10, disconnectLabel, reconnectBtn);
        card.getStyleClass().add("disconnect-card");
        card.setAlignment(Pos.CENTER);

        disconnectOverlay = new StackPane(card);
        disconnectOverlay.getStyleClass().add("disconnect-overlay");
        StackPane.setAlignment(disconnectOverlay, Pos.CENTER);

        terminalHost.getChildren().setAll(disconnectOverlay);
        terminalHost.requestLayout();
        Platform.requestNextPulse();
        log.info("[Terminal] Disconnect overlay displayed for session {} in {} ms",
                sshSession.sessionId(), (System.nanoTime() - startedAt) / 1_000_000);
    }

    private void closeTerminalHandlesAfterDisconnect(ShellTtyConnector.DisconnectReason reason) {
        List<TerminalViewHandle> snapshot = new ArrayList<>(handles);
        log.info("[Terminal] Closing {} terminal handle(s) after disconnect for session {}, reason={}",
                snapshot.size(), sshSession.sessionId(), reason);
        for (TerminalViewHandle handle : snapshot) {
            CompletableFuture<Void> closeFuture;
            if (handle instanceof DefaultTerminalViewHandle defaultHandle) {
                closeFuture = defaultHandle.closeAfterDisconnectAsync();
            } else {
                closeFuture = handle.closeAsync();
            }
            closeFuture.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    log.warn("[Terminal] Terminal handle cleanup failed for session {}: {}",
                            sshSession.sessionId(), throwable.getMessage());
                }
            });
        }
    }

    /** 隐藏断连提示覆盖层（重连时调用） */
    private void hideDisconnectOverlay() {
        if (disconnectOverlay != null) {
            terminalHost.getChildren().remove(disconnectOverlay);
            disconnectOverlay = null;
        }
        // 重连后恢复终端节点可见（如果还在用旧节点）
        if (primaryNode != null && disconnected) {
            primaryNode.setVisible(true);
            primaryNode.setManaged(true);
        }
        disconnected = false;
    }

    /** 标记已重连（清除断连状态，由 SessionWorkspaceTab 在重连成功后调用） */
    public void markReconnected() {
        hideDisconnectOverlay();
        if (sessionStatusBar != null && (nativeMonitor || sessionStatusBar.isVisible())) {
            startStatusSampling();
        }
    }

    /** 返回终端是否处于断连状态 */
    public boolean isDisconnected() {
        return disconnected;
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────


    private StackPane buildSessionStatusArea() {
        ipValueLabel = statusValueLabel("--");
        osValueLabel = statusValueLabel("--");
        cpuValueLabel = statusValueLabel("--");
        memValueLabel = statusValueLabel("--");
        diskValueLabel = statusValueLabel("--");

        HBox ip = statusSection("/icons/ip.svg", i18nService.get("sysinfo.network"), ipValueLabel, "ip",
                NETWORK_STATUS_WIDTH);
        HBox os = statusSection("/icons/system.svg", i18nService.get("sysinfo.os"), osValueLabel, "os",
                OS_STATUS_WIDTH);
        HBox cpu = statusSection("/icons/cpu.svg", i18nService.get("sysinfo.cpu"), cpuValueLabel, "cpu",
                CPU_STATUS_WIDTH);
        HBox mem = statusSection("/icons/memory-solid.svg", i18nService.get("sysinfo.mem"), memValueLabel, "mem",
                MEMORY_STATUS_WIDTH);
        HBox disk = statusSection("/icons/folder.svg", i18nService.get("sysinfo.disk"), diskValueLabel, "disk",
                DISK_STATUS_WIDTH);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        serverTimeLabel = new Label("--:--:-- --");
        serverTimeLabel.getStyleClass().add("terminal-server-time");
        serverTimeLabel.setMinWidth(118);
        serverTimeLabel.setPrefWidth(118);
        serverTimeLabel.setMaxWidth(118);
        serverTimeLabel.setAlignment(Pos.CENTER_RIGHT);
        HBox.setMargin(serverTimeLabel, new Insets(0, 8, 0, 8));
        Button hideButton = iconBtn("/icons/collapse-up.svg", i18nService.get("terminal.statusBar.hide"),
                () -> setStatusBarVisible(false));
        hideButton.getStyleClass().add("terminal-status-hide");

        sessionStatusBar = new HBox(2, ip, makeSep(), os, makeSep(), cpu, makeSep(), mem, makeSep(), disk,
                spacer, serverTimeLabel, hideButton);
        sessionStatusBar.getStyleClass().add("terminal-status-bar");

        statusBarRevealButton = iconBtn("/icons/collapse-up.svg", i18nService.get("terminal.statusBar.show"),
                () -> setStatusBarVisible(true));
        statusBarRevealButton.getStyleClass().add("terminal-status-reveal");
        if (statusBarRevealButton.getGraphic() != null) {
            statusBarRevealButton.getGraphic().setRotate(180);
        }
        statusBarRevealButton.setManaged(false);
        statusBarRevealButton.setVisible(false);

        sessionStatusArea = new StackPane(sessionStatusBar, statusBarRevealButton);
        sessionStatusArea.getStyleClass().add("terminal-status-area");
        StackPane.setAlignment(sessionStatusBar, Pos.CENTER_LEFT);
        StackPane.setAlignment(statusBarRevealButton, Pos.CENTER_RIGHT);
        StackPane.setMargin(statusBarRevealButton, new Insets(2, 7, 2, 0));
        return sessionStatusArea;
    }

    private Label statusValueLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("terminal-status-value");
        label.setMinWidth(0);
        label.setMaxWidth(230);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        return label;
    }

    private HBox statusSection(String iconPath, String title, Label value, String type, double width) {
        Region icon = loadSvgShape(iconPath, 12);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("terminal-status-title");
        HBox box = icon == null ? new HBox(5, titleLabel, value) : new HBox(5, icon, titleLabel, value);
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        HBox.setHgrow(value, Priority.ALWAYS);
        box.getStyleClass().add("terminal-status-item");
        javafx.scene.control.Tooltip.install(box,
                new javafx.scene.control.Tooltip(i18nService.get("terminal.statusBar.details")));
        box.setOnMouseClicked(event -> {
            event.consume();
            toggleFloatingCard(box, type);
        });
        return box;
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

    // ── Realtime status sampling ─────────────────────────────────────────────

    private void startStatusSampling() {
        if (primaryNode == null || sessionStatusBar == null || (!nativeMonitor && !sessionStatusBar.isVisible()) || disconnected) {
            return;
        }
        if (statusRefreshTimeline == null) {
            statusRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(3), event -> refreshStatusSummary()));
            statusRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        if (serverClockTimeline == null) {
            serverClockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateServerClockLabel()));
            serverClockTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        if (statusRefreshTimeline.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            resetStatusSamplingBaseline();
            refreshStatusSummary();
            statusRefreshTimeline.play();
        }
        if (serverClockTimeline.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            updateServerClockLabel();
            serverClockTimeline.play();
        }
    }

    private void stopStatusSampling() {
        statusSamplingGeneration++;
        if (statusRefreshTimeline != null) {
            statusRefreshTimeline.stop();
        }
        if (serverClockTimeline != null) {
            serverClockTimeline.stop();
        }
        statusRefreshInFlight = false;
        resetStatusSamplingBaseline();
    }

    private void resetStatusSamplingBaseline() {
        previousRxBytes = -1;
        previousTxBytes = -1;
        previousCpuTotal = -1;
        previousCpuIdle = -1;
        previousSampleNanos = -1;
    }

    private void refreshStatusSummary() {
        // Tab 未选中时不向远端持续发命令；再次显示后由定时器自动恢复。
        if (statusRefreshInFlight || disconnected || (getScene() == null && nativeTerminalHost == null)
                || sessionStatusBar == null || (!nativeMonitor && !sessionStatusBar.isVisible())) {
            return;
        }
        statusRefreshInFlight = true;
        long generation = statusSamplingGeneration;
        sshSession.execute(new CommandRequest(buildStatusSummaryCommand(), STATUS_COMMAND_TIMEOUT, false, null))
                .whenComplete((output, throwable) -> FxThread.run(() -> {
                    if (generation != statusSamplingGeneration || sessionStatusBar == null) {
                        return;
                    }
                    statusRefreshInFlight = false;
                    if (throwable != null) {
                        publishMonitorSnapshot(throwable.getMessage());
                        log.debug("Unable to refresh terminal status for session {}: {}",
                                sshSession.sessionId(), throwable.getMessage());
                        return;
                    }
                    applyStatusSummary(parseStatusSummary(output.stdout()));
                }));
    }

    /**
     * 单次采集全部摘要，减少 SSH 往返。Linux 使用 proc/sysfs，macOS 与 BSD 命令作为降级路径。
     * 输出采用带前缀的制表符协议，避免受远端本地化文本影响。
     */
    private String buildStatusSummaryCommand() {
        return """
                LC_ALL=C
                if [ -r /etc/os-release ]; then
                  . /etc/os-release
                  jl_os="${PRETTY_NAME:-${NAME:-Linux}}"
                else
                  jl_os="$(uname -s 2>/dev/null) $(uname -r 2>/dev/null)"
                fi
                jl_host="$(hostname 2>/dev/null || uname -n 2>/dev/null)"
                jl_kernel="$(uname -srmo 2>/dev/null || uname -a 2>/dev/null)"
                jl_iface="$(ip route show default 2>/dev/null | awk 'NR==1 {print $5}')"
                if [ -z "$jl_iface" ]; then
                  jl_iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
                fi
                jl_ip=""
                if [ -n "$jl_iface" ]; then
                  jl_ip="$(ip -o -4 addr show dev "$jl_iface" 2>/dev/null | awk 'NR==1 {split($4,a,"/"); print a[1]}')"
                  [ -n "$jl_ip" ] || jl_ip="$(ipconfig getifaddr "$jl_iface" 2>/dev/null)"
                fi
                [ -n "$jl_ip" ] || jl_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
                jl_rx=0; jl_tx=0
                if [ -n "$jl_iface" ] && [ -r "/sys/class/net/$jl_iface/statistics/rx_bytes" ]; then
                  jl_rx="$(cat "/sys/class/net/$jl_iface/statistics/rx_bytes" 2>/dev/null)"
                  jl_tx="$(cat "/sys/class/net/$jl_iface/statistics/tx_bytes" 2>/dev/null)"
                elif [ -n "$jl_iface" ]; then
                  set -- $(netstat -ibn 2>/dev/null | awk -v i="$jl_iface" '$1==i && $7 ~ /^[0-9]+$/ {rx=$7; tx=$10} END {print rx+0, tx+0}')
                  jl_rx="${1:-0}"; jl_tx="${2:-0}"
                fi
                set -- $(awk '/^cpu / {idle=$5+$6; total=0; for(i=2;i<=NF;i++) total+=$i; print total, idle; exit}' /proc/stat 2>/dev/null)
                jl_cpu_total="${1:-0}"; jl_cpu_idle="${2:-0}"
                jl_load="$(awk '{print $1}' /proc/loadavg 2>/dev/null)"
                [ -n "$jl_load" ] || jl_load="$(uptime 2>/dev/null | sed 's/.*load averages*[: ] *//' | cut -d, -f1 | xargs)"
                jl_cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 0)"
                set -- $(awk '/MemTotal:/{t=$2} /MemAvailable:/{a=$2} END{if(t>0) printf "%.0f %.0f\\n",t*1024,(t-a)*1024}' /proc/meminfo 2>/dev/null)
                jl_mem_total="${1:-0}"; jl_mem_used="${2:-0}"
                if [ "$jl_mem_total" = 0 ]; then
                  jl_mem_total="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
                  jl_page_size="$(pagesize 2>/dev/null || echo 4096)"
                  jl_mem_used="$(vm_stat 2>/dev/null | awk -v p="$jl_page_size" '
                    /Pages active:/ {a=$3} /Pages wired down:/ {w=$4} /Pages occupied by compressor:/ {c=$5}
                    END {gsub("\\.","",a); gsub("\\.","",w); gsub("\\.","",c); printf "%.0f\\n",(a+w+c)*p}')"
                  [ -n "$jl_mem_used" ] || jl_mem_used=0
                fi
                set -- $(df -Pk / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); printf "%.0f %.0f %s\\n",$2*1024,$3*1024,$5}')
                jl_disk_total="${1:-0}"; jl_disk_used="${2:-0}"; jl_disk_pct="${3:-0}"
                jl_time_epoch="$(date +%s 2>/dev/null || echo 0)"
                jl_time_zone="$(date +%Z 2>/dev/null || echo UTC)"
                jl_time_offset="$(date +%z 2>/dev/null || echo +0000)"
                printf 'JL_OS\t%s\n' "$jl_os"
                printf 'JL_HOST\t%s\n' "$jl_host"
                printf 'JL_KERNEL\t%s\n' "$jl_kernel"
                printf 'JL_NET\t%s\t%s\t%s\t%s\n' "$jl_iface" "$jl_ip" "$jl_rx" "$jl_tx"
                printf 'JL_CPU\t%s\t%s\t%s\t%s\n' "$jl_cpu_total" "$jl_cpu_idle" "$jl_load" "$jl_cores"
                printf 'JL_MEM\t%s\t%s\n' "$jl_mem_total" "$jl_mem_used"
                printf 'JL_DISK\t%s\t%s\t%s\n' "$jl_disk_total" "$jl_disk_used" "$jl_disk_pct"
                printf 'JL_TIME\t%s\t%s\t%s\n' "$jl_time_epoch" "$jl_time_zone" "$jl_time_offset"
                """;
    }

    private StatusSummary parseStatusSummary(String raw) {
        String os = "--";
        String host = "--";
        String kernel = "--";
        String iface = "--";
        String ip = "--";
        long rx = 0;
        long tx = 0;
        long cpuTotal = 0;
        long cpuIdle = 0;
        double load = -1;
        int cores = 0;
        long memTotal = 0;
        long memUsed = 0;
        long diskTotal = 0;
        long diskUsed = 0;
        int diskPercent = 0;
        long remoteEpochSeconds = 0;
        String remoteTimezone = "--";
        String remoteTimezoneOffset = "+0000";
        for (String line : raw.split("\\R")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 2) continue;
            switch (parts[0]) {
                case "JL_OS" -> os = parts[1];
                case "JL_HOST" -> host = parts[1];
                case "JL_KERNEL" -> kernel = parts[1];
                case "JL_NET" -> {
                    if (parts.length >= 5) {
                        iface = parts[1].isBlank() ? "--" : parts[1];
                        ip = parts[2].isBlank() ? "--" : parts[2];
                        rx = parseLong(parts[3]);
                        tx = parseLong(parts[4]);
                    }
                }
                case "JL_CPU" -> {
                    if (parts.length >= 5) {
                        cpuTotal = parseLong(parts[1]);
                        cpuIdle = parseLong(parts[2]);
                        load = parseDouble(parts[3]);
                        cores = (int) parseLong(parts[4]);
                    }
                }
                case "JL_MEM" -> {
                    if (parts.length >= 3) {
                        memTotal = parseLong(parts[1]);
                        memUsed = parseLong(parts[2]);
                    }
                }
                case "JL_DISK" -> {
                    if (parts.length >= 4) {
                        diskTotal = parseLong(parts[1]);
                        diskUsed = parseLong(parts[2]);
                        diskPercent = (int) parseLong(parts[3]);
                    }
                }
                case "JL_TIME" -> {
                    if (parts.length >= 4) {
                        remoteEpochSeconds = parseLong(parts[1]);
                        remoteTimezone = parts[2].isBlank() ? "--" : parts[2];
                        remoteTimezoneOffset = parts[3].isBlank() ? "+0000" : parts[3];
                    }
                }
                default -> { }
            }
        }
        return new StatusSummary(os, host, kernel, iface, ip, rx, tx, cpuTotal, cpuIdle, load, cores,
                memTotal, memUsed, diskTotal, diskUsed, diskPercent,
                remoteEpochSeconds, remoteTimezone, remoteTimezoneOffset);
    }

    private void applyStatusSummary(StatusSummary summary) {
        lastStatusSummary = summary;
        long now = System.nanoTime();
        if (summary.remoteEpochSeconds() > 0) {
            remoteClockEpochSeconds = summary.remoteEpochSeconds();
            remoteClockSampleNanos = now;
            remoteClockZone = summary.remoteTimezone();
            remoteClockOffset = summary.remoteTimezoneOffset();
            updateServerClockLabel();
        }
        if (previousSampleNanos > 0 && now > previousSampleNanos) {
            double elapsedSeconds = (now - previousSampleNanos) / 1_000_000_000.0;
            if (summary.rxBytes() >= previousRxBytes && summary.txBytes() >= previousTxBytes
                    && previousRxBytes >= 0 && previousTxBytes >= 0) {
                currentDownloadRate = formatRate((summary.rxBytes() - previousRxBytes) / elapsedSeconds);
                currentUploadRate = formatRate((summary.txBytes() - previousTxBytes) / elapsedSeconds);
                monitorNetworkRate = (summary.rxBytes() - previousRxBytes + summary.txBytes() - previousTxBytes) / elapsedSeconds;
            }
        }
        if (previousCpuTotal >= 0 && summary.cpuTotal() > previousCpuTotal) {
            long totalDelta = summary.cpuTotal() - previousCpuTotal;
            long idleDelta = summary.cpuIdle() - previousCpuIdle;
            int usage = (int) Math.round(100.0 * Math.max(0, totalDelta - idleDelta) / totalDelta);
            currentCpuPercent = clampPercent(usage);
            currentCpuUsage = currentCpuPercent + "%";
        } else if (summary.load() >= 0 && summary.cores() > 0) {
            int pressure = (int) Math.round(Math.min(100, summary.load() / summary.cores() * 100));
            currentCpuPercent = clampPercent(pressure);
            currentCpuUsage = currentCpuPercent + "%";
        }

        previousSampleNanos = now;
        previousRxBytes = summary.rxBytes();
        previousTxBytes = summary.txBytes();
        previousCpuTotal = summary.cpuTotal();
        previousCpuIdle = summary.cpuIdle();

        ipValueLabel.setText("↓ " + currentDownloadRate + "  ↑ " + currentUploadRate);
        ipValueLabel.setTooltip(new javafx.scene.control.Tooltip(summary.iface() + " · " + summary.ip()));
        osValueLabel.setText(compactOsName(summary.os()));
        osValueLabel.setTooltip(new javafx.scene.control.Tooltip(summary.host() + " · " + summary.kernel()));
        cpuValueLabel.setText(currentCpuUsage + (summary.load() >= 0 ? " · L " + formatDecimal(summary.load()) : ""));
        memValueLabel.setText(formatUsage(summary.memUsed(), summary.memTotal()));
        diskValueLabel.setText(summary.diskPercent() + "% · " + formatBytes(summary.diskUsed()) + "/"
                + formatBytes(summary.diskTotal()));
        updateResourceSeverity(cpuValueLabel, currentCpuPercent);
        updateResourceSeverity(memValueLabel, usagePercent(summary.memUsed(), summary.memTotal()));
        updateResourceSeverity(diskValueLabel, summary.diskPercent());
        monitorSample++;
        publishMonitorSnapshot(null);
        updateFloatingLiveSummary(summary);
        refreshOpenProcessDetails();
    }

    private void updateServerClockLabel() {
        if (serverTimeLabel == null || remoteClockEpochSeconds <= 0 || remoteClockSampleNanos <= 0) return;
        long elapsedSeconds = Math.max(0, (System.nanoTime() - remoteClockSampleNanos) / 1_000_000_000L);
        java.time.ZoneOffset offset = parseRemoteZoneOffset(remoteClockOffset);
        String time = java.time.Instant.ofEpochSecond(remoteClockEpochSeconds + elapsedSeconds)
                .atOffset(offset)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ROOT));
        serverTimeLabel.setText(time + " " + remoteClockZone);
        publishMonitorSnapshot(monitorSnapshot.get() == null ? null : monitorSnapshot.get().failure());
    }

    static java.time.ZoneOffset parseRemoteZoneOffset(String rawOffset) {
        String value = rawOffset == null ? "" : rawOffset.strip();
        if (value.matches("[+-]\\d{4}")) {
            value = value.substring(0, 3) + ":" + value.substring(3);
        }
        try {
            return java.time.ZoneOffset.of(value);
        } catch (java.time.DateTimeException ignored) {
            return java.time.ZoneOffset.UTC;
        }
    }

    private void updateFloatingLiveSummary(StatusSummary summary) {
        if (floatingPopup == null || !floatingPopup.isShowing() || floatingLiveLabels.isEmpty()) return;
        setFloatingLiveText("network.interface", summary.iface() + " · " + summary.ip());
        setFloatingLiveText("network.speed", "↓ " + currentDownloadRate + "   ↑ " + currentUploadRate);
        setFloatingLiveText("os.hostname", summary.host());
        setFloatingLiveText("os.kernel", summary.kernel());
        setFloatingLiveText("cpu.pressure", currentCpuUsage + (summary.load() >= 0
                ? " · Load " + formatDecimal(summary.load()) : ""));
        setFloatingLiveText("mem.usage", formatUsage(summary.memUsed(), summary.memTotal()));
        setFloatingLiveText("disk.usage", summary.diskPercent() + "% · " + formatBytes(summary.diskUsed())
                + "/" + formatBytes(summary.diskTotal()));
        updateFloatingUsageBar("cpu", currentCpuPercent);
        updateFloatingUsageBar("mem", usagePercent(summary.memUsed(), summary.memTotal()));
        updateFloatingUsageBar("disk", summary.diskPercent());
    }

    private void setFloatingLiveText(String key, String value) {
        Label label = floatingLiveLabels.get(key);
        if (label != null) label.setText(value);
    }

    private static String compactOsName(String os) {
        if (os == null || os.isBlank()) return "--";
        return os.length() <= 28 ? os : os.substring(0, 27) + "…";
    }

    private static String formatUsage(long used, long total) {
        if (total <= 0) return "--";
        int percent = (int) Math.round(used * 100.0 / total);
        return percent + "% · " + formatBytes(used) + "/" + formatBytes(total);
    }

    private static int usagePercent(long used, long total) {
        if (total <= 0) return -1;
        return clampPercent((int) Math.round(used * 100.0 / total));
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private void updateResourceSeverity(Label label, int percent) {
        if (label == null) return;
        label.getStyleClass().removeAll("resource-warning", "resource-danger");
        if (percent >= dangerThreshold) {
            label.getStyleClass().add("resource-danger");
        } else if (percent >= warningThreshold) {
            label.getStyleClass().add("resource-warning");
        }
    }

    private static String formatRate(double bytesPerSecond) {
        return formatBytes((long) Math.max(0, bytesPerSecond)) + "/s";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (value >= 10 ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : String.format(java.util.Locale.ROOT, "%.1f", value)) + " " + units[unit];
    }

    private static String formatDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null || value.isBlank() ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null || value.isBlank() ? "-1" : value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record StatusSummary(
            String os, String host, String kernel, String iface, String ip,
            long rxBytes, long txBytes, long cpuTotal, long cpuIdle, double load, int cores,
            long memTotal, long memUsed, long diskTotal, long diskUsed, int diskPercent,
            long remoteEpochSeconds, String remoteTimezone, String remoteTimezoneOffset
    ) { }

    record ProcessUsage(
            String pid,
            String user,
            double cpuPercent,
            double memPercent,
            long residentBytes,
            String command
    ) { }

    record ResourceThresholds(int warning, int danger) {
        static ResourceThresholds of(int warning, int danger) {
            int normalizedWarning = Math.max(1, Math.min(98, warning));
            int normalizedDanger = Math.max(normalizedWarning + 1, Math.min(100, danger));
            return new ResourceThresholds(normalizedWarning, normalizedDanger);
        }
    }

    private record LiveUsageBar(ProgressBar progress, Label value) { }

    // ── Floating card (click to show, click elsewhere to hide) ───────────────

    private void toggleFloatingCard(HBox anchor, String type) {
        // If same type is showing, just hide
        if (floatingPopup != null && floatingPopup.isShowing() && java.util.Objects.equals(floatingPendingType, type)) {
            hideFloatingCard();
            return;
        }
        hideFloatingCard();

        floatingPendingType = type;
        floatingAnchor = anchor;
        floatingProcessRows = null;
        floatingProcessRefreshInFlight = false;
        floatingProcessGeneration++;

        // Loading indicator
        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(20, 20);

        floatingContent = new VBox(4, loading);
        floatingContent.setAlignment(Pos.CENTER);
        floatingContent.setPadding(new Insets(10));
        floatingContent.setMinWidth(0);
        floatingContent.setPrefWidth(FLOATING_CARD_WIDTH);
        floatingContent.setMaxWidth(FLOATING_CARD_WIDTH);

        floatingScroll = new ScrollPane(floatingContent);
        floatingScroll.getStyleClass().addAll("hover-card", "hover-card-scroll");
        floatingScroll.setFitToWidth(true);
        floatingScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        floatingScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        floatingScroll.setPannable(false);
        floatingScroll.setMinWidth(FLOATING_CARD_WIDTH);
        floatingScroll.setPrefWidth(FLOATING_CARD_WIDTH);
        floatingScroll.setMaxWidth(FLOATING_CARD_WIDTH);
        floatingScroll.setPrefViewportWidth(FLOATING_CARD_WIDTH);
        floatingScroll.setPrefViewportHeight(72);
        floatingScroll.setMaxHeight(480);

        floatingPopup = new javafx.stage.Popup();
        floatingPopup.getContent().setAll(floatingScroll);
        floatingPopup.setAutoHide(true);
        floatingPopup.setAutoFix(true);
        floatingPopup.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.CONTENT_BOTTOM_LEFT);

        Point2D pos = anchor.localToScreen(0, 0);
        if (pos == null) {
            hideFloatingCard();
            return;
        }
        floatingPopup.show(anchor, pos.getX(), floatingCardBottomY(pos));
        Platform.runLater(this::positionFloatingCardAboveAnchor);

        // Fetch and format
        String cmd = buildDetailCmd(type);
        sshSession.execute(new CommandRequest(cmd, java.time.Duration.ofSeconds(10), false, null))
                .thenAccept(output -> FxThread.run(() -> {
                    if (floatingPopup != null && floatingPopup.isShowing() && java.util.Objects.equals(floatingPendingType, type)) {
                        if ("disk".equals(type)) {
                            int stdoutLength = output.stdout() == null ? 0 : output.stdout().length();
                            String stderr = output.stderr() == null ? "" : output.stderr().strip();
                            if (stdoutLength == 0 || (output.exitCode() != null && output.exitCode() != 0)) {
                                log.warn("Storage detail command returned no usable data: exitCode={}, stderr={}",
                                        output.exitCode(), stderr);
                            } else {
                                log.debug("Storage detail command completed: exitCode={}, stdoutLength={}",
                                        output.exitCode(), stdoutLength);
                            }
                        }
                        renderFormattedContent(type, output.stdout());
                        updateFloatingCardViewport();
                        positionFloatingCardAboveAnchor();
                    }
                }))
                .exceptionally(ex -> {
                    FxThread.run(() -> {
                        if (floatingPopup != null && floatingPopup.isShowing()) {
                            floatingContent.getChildren().setAll(kvRow("Error", ex.getMessage()));
                            updateFloatingCardViewport();
                            positionFloatingCardAboveAnchor();
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
        floatingAnchor = null;
        floatingScroll = null;
        floatingLiveLabels.clear();
        floatingUsageBars.clear();
        floatingProcessRows = null;
        floatingProcessRefreshInFlight = false;
        floatingProcessGeneration++;
    }

    private void updateFloatingCardViewport() {
        if (floatingScroll == null || floatingContent == null) return;
        floatingContent.applyCss();
        floatingContent.layout();
        double contentHeight = floatingContent.prefHeight(FLOATING_CARD_WIDTH);
        double maxHeight = 480;
        if (floatingAnchor != null && floatingAnchor.getScene() != null) {
            Point2D anchorPosition = floatingAnchor.localToScreen(0, 0);
            if (anchorPosition != null) {
                javafx.stage.Screen screen = javafx.stage.Screen.getScreensForRectangle(
                                anchorPosition.getX(), anchorPosition.getY(), 1, 1)
                        .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
                javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
                double availableHeight = floatingCardBottomY(anchorPosition) - bounds.getMinY() - 8;
                maxHeight = Math.min(maxHeight,
                        Math.min(bounds.getHeight() * 0.62, Math.max(72, availableHeight)));
            }
        }
        floatingScroll.setPrefViewportHeight(Math.max(72, Math.min(maxHeight, contentHeight)));
        floatingScroll.setMaxHeight(maxHeight);
        floatingScroll.requestLayout();
        Platform.runLater(this::positionFloatingCardAboveAnchor);
    }

    private void positionFloatingCardAboveAnchor() {
        if (floatingPopup == null || !floatingPopup.isShowing() || floatingAnchor == null) return;
        Point2D anchorPosition = floatingAnchor.localToScreen(0, 0);
        if (anchorPosition == null) return;
        javafx.stage.Screen screen = javafx.stage.Screen.getScreensForRectangle(
                        anchorPosition.getX(), anchorPosition.getY(), 1, 1)
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
        javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
        double popupWidth = Math.max(FLOATING_CARD_WIDTH, floatingPopup.getWidth());
        double popupHeight = Math.max(120, floatingPopup.getHeight());
        double preferredX = anchorPosition.getX();
        if (preferredX + popupWidth > bounds.getMaxX() - 8) {
            preferredX = anchorPosition.getX() + floatingAnchor.getWidth() - popupWidth;
        }
        double x = Math.max(bounds.getMinX() + 8,
                Math.min(preferredX, bounds.getMaxX() - popupWidth - 8));
        double bottomY = floatingCardBottomY(anchorPosition);
        if (bottomY - popupHeight < bounds.getMinY() + 8) {
            bottomY = bounds.getMinY() + popupHeight + 8;
        }
        floatingPopup.setAnchorX(x);
        floatingPopup.setAnchorY(bottomY);
    }

    private double floatingCardBottomY(Point2D fallbackAnchorPosition) {
        if (sessionStatusArea != null && sessionStatusArea.getScene() != null) {
            Point2D statusBarPosition = sessionStatusArea.localToScreen(0, 0);
            if (statusBarPosition != null) {
                return statusBarPosition.getY() - FLOATING_CARD_BOTTOM_GAP;
            }
        }
        return fallbackAnchorPosition.getY() - FLOATING_CARD_BOTTOM_GAP;
    }

    private String buildDetailCmd(String type) {
        switch (type) {
            case "ip":
                return "echo '---ADDRESS---'; (ip -brief addr show 2>/dev/null || ifconfig 2>/dev/null); "
                        + "echo '---ROUTE---'; (ip route show 2>/dev/null || netstat -rn 2>/dev/null)";
            case "os":
                return "hostnamectl 2>/dev/null || (echo \"Hostname: $(hostname)\"; "
                        + "echo \"Operating System: $(uname -s) $(uname -r)\"; "
                        + "echo \"Architecture: $(uname -m)\"; "
                        + "echo \"Kernel: $(uname -r)\")";
            case "cpu":
                return "echo \"Load average: $(cat /proc/loadavg 2>/dev/null | awk '{print $1, $2, $3}' || uptime)\"; "
                        + "lscpu 2>/dev/null || (echo \"Model name: $(sysctl -n machdep.cpu.brand_string 2>/dev/null)\"; "
                        + "echo \"CPU(s): $(sysctl -n hw.logicalcpu 2>/dev/null)\"); "
                        + "echo '---PROCESSES---'; " + buildProcessCommand("cpu");
            case "mem":
                return "free -h 2>/dev/null || (echo \"Total: $(sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1073741824)\" GB\"}')\"; "
                        + "vm_stat 2>/dev/null); "
                        + "echo \"---SWAP---\"; "
                        + "swapon --show 2>/dev/null || echo \"Swap: $(sysctl -n vm.swapusage 2>/dev/null || echo N/A)\"; "
                        + "echo '---PROCESSES---'; " + buildProcessCommand("mem");
            case "disk":
                return "df -Pk 2>/dev/null || df -k 2>/dev/null";
            default:
                return "echo N/A";
        }
    }

    private String buildProcessCommand(String type) {
        int sortColumn = "mem".equals(type) ? 5 : 3;
        return "{ ps -Ao pid=,user=,pcpu=,pmem=,rss=,comm= 2>/dev/null "
                + "|| ps aux 2>/dev/null | awk 'NR>1 {print $2, $1, $3, $4, $6, $11}'; } "
                + "| sort -k" + sortColumn + "," + sortColumn + "nr | head -n 10";
    }

    // ── Formatted content rendering ──────────────────────────────────────────

    private void renderFormattedContent(String type, String raw) {
        floatingContent.getChildren().clear();
        floatingContent.setAlignment(Pos.TOP_LEFT);
        floatingLiveLabels.clear();

        if (lastStatusSummary != null) {
            switch (type) {
                case "ip" -> {
                    floatingContent.getChildren().add(liveKvRow("network.interface",
                            i18nService.get("sysinfo.networkInterface"),
                            lastStatusSummary.iface() + " · " + lastStatusSummary.ip()));
                    floatingContent.getChildren().add(liveKvRow("network.speed",
                            i18nService.get("sysinfo.realtimeSpeed"),
                            "↓ " + currentDownloadRate + "   ↑ " + currentUploadRate));
                    floatingContent.getChildren().add(sectionHeader(i18nService.get("sysinfo.networkDetails")));
                }
                case "os" -> {
                    floatingContent.getChildren().add(liveKvRow("os.hostname",
                            i18nService.get("sysinfo.hostname"), lastStatusSummary.host()));
                    floatingContent.getChildren().add(liveKvRow("os.kernel",
                            i18nService.get("sysinfo.kernel"), lastStatusSummary.kernel()));
                }
                case "cpu" -> {
                    floatingContent.getChildren().add(liveKvRow("cpu.pressure",
                            i18nService.get("sysinfo.currentPressure"),
                            currentCpuUsage + (lastStatusSummary.load() >= 0
                                    ? " · Load " + formatDecimal(lastStatusSummary.load()) : "")));
                    floatingContent.getChildren().add(liveUsageBar("cpu", currentCpuPercent));
                }
                case "mem" -> {
                    floatingContent.getChildren().add(liveKvRow("mem.usage",
                            i18nService.get("sysinfo.currentUsage"),
                            formatUsage(lastStatusSummary.memUsed(), lastStatusSummary.memTotal())));
                    floatingContent.getChildren().add(liveUsageBar("mem",
                            usagePercent(lastStatusSummary.memUsed(), lastStatusSummary.memTotal())));
                }
                case "disk" -> {
                    floatingContent.getChildren().add(liveKvRow("disk.usage",
                            i18nService.get("sysinfo.currentUsage"),
                            lastStatusSummary.diskPercent() + "% · " + formatBytes(lastStatusSummary.diskUsed())
                                    + "/" + formatBytes(lastStatusSummary.diskTotal())));
                    floatingContent.getChildren().add(liveUsageBar("disk", lastStatusSummary.diskPercent()));
                }
                default -> { }
            }
        }

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

            if (line.equals("---ADDRESS---")) {
                floatingContent.getChildren().add(sectionHeader(i18nService.get("sysinfo.addresses")));
                continue;
            }
            if (line.equals("---ROUTE---")) {
                floatingContent.getChildren().add(sectionHeader(i18nService.get("sysinfo.routes")));
                continue;
            }

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
        String[] sections = (raw == null ? "" : raw).split("---PROCESSES---", 2);
        String cpuInfo = sections[0];
        String processes = sections.length > 1 ? sections[1] : "";
        // lscpu: "Model name:            Intel..."
        for (String line : cpuInfo.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                // Only show interesting fields
                if (key.matches("Load average|Model name|CPU\\(s\\)|Thread|Core|Socket|Architecture|CPU MHz|CPU max MHz|L[123] cache|Vendor ID|CPU family")) {
                    floatingContent.getChildren().add(kvRow(key, val));
                }
            }
        }
        renderProcessTable("cpu", processes);
    }

    private void renderMemContent(String raw) {
        String[] processSections = (raw == null ? "" : raw).split("---PROCESSES---", 2);
        String memoryInfo = processSections[0];
        String processes = processSections.length > 1 ? processSections[1] : "";
        // free -h output or vm_stat
        String[] sections = memoryInfo.split("---SWAP---");
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
        renderProcessTable("mem", processes);
    }

    private void renderProcessTable(String type, String raw) {
        Label title = sectionHeader(i18nService.get(
                "mem".equals(type) ? "sysinfo.topMemoryProcesses" : "sysinfo.topCpuProcesses"));
        Label copyHint = new Label(i18nService.get("sysinfo.process.copyHint"));
        copyHint.getStyleClass().add("process-copy-hint");
        HBox titleRow = new HBox(8, title, copyHint);
        titleRow.getStyleClass().add("process-table-title");
        titleRow.setAlignment(Pos.BASELINE_LEFT);
        floatingContent.getChildren().add(titleRow);

        HBox header;
        if ("mem".equals(type)) {
            header = new HBox(8,
                    processPidHeader(48),
                    processHeader(i18nService.get("sysinfo.process.user"), 62, Pos.CENTER_LEFT),
                    processHeader(i18nService.get("sysinfo.process.memorySize"), 68, Pos.CENTER_RIGHT),
                    processHeader(i18nService.get("sysinfo.process.memoryPercent"), 46, Pos.CENTER_RIGHT),
                    processHeader(i18nService.get("sysinfo.process.command"), 96, Pos.CENTER_LEFT));
        } else {
            header = new HBox(8,
                    processPidHeader(48),
                    processHeader(i18nService.get("sysinfo.process.user"), 68, Pos.CENTER_LEFT),
                    processHeader("CPU%", 48, Pos.CENTER_RIGHT),
                    processHeader(i18nService.get("sysinfo.process.command"), 168, Pos.CENTER_LEFT));
        }
        header.getStyleClass().addAll("process-table-row", "process-table-header");

        floatingProcessRows = new VBox();
        floatingProcessRows.getStyleClass().add("process-table-rows");
        updateProcessRows(type, parseTopProcesses(raw));

        VBox table = new VBox(header, floatingProcessRows);
        table.getStyleClass().add("process-table");
        table.setMaxWidth(Double.MAX_VALUE);
        floatingContent.getChildren().add(table);
    }

    private Label processHeader(String text, double width, Pos alignment) {
        Label label = new Label(text);
        label.getStyleClass().add("process-table-header-label");
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        label.setAlignment(alignment);
        return label;
    }

    private void updateProcessRows(String type, List<ProcessUsage> processes) {
        if (floatingProcessRows == null) return;
        List<Node> rows = new ArrayList<>();
        for (ProcessUsage process : processes) {
            HBox row;
            Label command;
            if ("mem".equals(type)) {
                command = processValue(process.command(), 96, Pos.CENTER_LEFT);
                row = new HBox(8,
                        processPidValue(process.pid(), 48),
                        processValue(process.user(), 62, Pos.CENTER_LEFT),
                        processValue(formatBytes(process.residentBytes()), 68, Pos.CENTER_RIGHT),
                        processValue(formatProcessPercent(process.memPercent()), 46, Pos.CENTER_RIGHT),
                        command);
            } else {
                command = processValue(process.command(), 168, Pos.CENTER_LEFT);
                row = new HBox(8,
                        processPidValue(process.pid(), 48),
                        processValue(process.user(), 68, Pos.CENTER_LEFT),
                        processValue(formatProcessPercent(process.cpuPercent()), 48, Pos.CENTER_RIGHT),
                        command);
            }
            command.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            command.setTooltip(new javafx.scene.control.Tooltip(process.command()));
            row.getStyleClass().add("process-table-row");
            rows.add(row);
        }
        if (rows.isEmpty()) {
            Label empty = new Label(i18nService.get("sysinfo.process.noData"));
            empty.getStyleClass().add("process-table-empty");
            rows.add(empty);
        }
        floatingProcessRows.getChildren().setAll(rows);
    }

    private Label processValue(String text, double width, Pos alignment) {
        Label label = new Label(text);
        label.getStyleClass().add("process-table-value");
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.setMaxWidth(width);
        label.setAlignment(alignment);
        return label;
    }

    private Label processPidHeader(double width) {
        Label header = processHeader(i18nService.get("sysinfo.process.pid"), width, Pos.CENTER_RIGHT);
        header.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("sysinfo.process.copyHint")));
        return header;
    }

    private Label processPidValue(String pid, double width) {
        Label label = processValue(pid, width, Pos.CENTER_RIGHT);
        label.getStyleClass().add("process-pid-value");
        String hint = i18nService.get("sysinfo.process.copyPid", pid);
        javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip(hint);
        label.setTooltip(tooltip);
        label.setAccessibleText(hint);
        label.setOnMouseClicked(event -> {
            event.consume();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(pid);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);

            String copied = i18nService.get("sysinfo.process.copied");
            label.setText(copied);
            label.getStyleClass().add("process-pid-copied");
            tooltip.setText(copied);
            javafx.animation.PauseTransition restore = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(1.2));
            restore.setOnFinished(ignored -> {
                label.setText(pid);
                label.getStyleClass().remove("process-pid-copied");
                tooltip.setText(hint);
            });
            restore.play();
        });
        return label;
    }

    private static String formatProcessPercent(double value) {
        return value < 0 ? "--" : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    static List<ProcessUsage> parseTopProcesses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<ProcessUsage> result = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 6);
            if (parts.length < 6 || !parts[0].matches("\\d+")) continue;
            result.add(new ProcessUsage(
                    parts[0],
                    parts[1],
                    parseDouble(parts[2]),
                    parseDouble(parts[3]),
                    parseLong(parts[4]) * 1024,
                    parts[5]
            ));
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private void refreshOpenProcessDetails() {
        String type = floatingPendingType;
        if (floatingPopup == null || !floatingPopup.isShowing() || floatingProcessRows == null
                || floatingProcessRefreshInFlight || !("cpu".equals(type) || "mem".equals(type))) {
            return;
        }
        floatingProcessRefreshInFlight = true;
        long generation = floatingProcessGeneration;
        sshSession.execute(new CommandRequest(buildProcessCommand(type), STATUS_COMMAND_TIMEOUT, false, null))
                .whenComplete((output, throwable) -> FxThread.run(() -> {
                    if (generation != floatingProcessGeneration) return;
                    floatingProcessRefreshInFlight = false;
                    if (throwable != null || output == null) {
                        log.debug("Unable to refresh top processes for session {}: {}",
                                sshSession.sessionId(), throwable == null ? "empty output" : throwable.getMessage());
                        return;
                    }
                    updateProcessRows(type, parseTopProcesses(output.stdout()));
                    updateFloatingCardViewport();
                    positionFloatingCardAboveAnchor();
                }));
    }

    private void renderDiskContent(String raw) {
        String[] lines = raw == null ? new String[0] : raw.split("\\R");
        VBox rows = new VBox();
        rows.getStyleClass().add("disk-table-rows");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;

            long totalBytes = parseLong(parts[1]) * 1024;
            long availableBytes = parseLong(parts[3]) * 1024;
            int usedPercent = (int) parseLong(parts[4].replace("%", ""));
            String mountPath = String.join(" ", java.util.Arrays.copyOfRange(parts, 5, parts.length))
                    .replace("\\040", " ");

            Label path = new Label(mountPath);
            path.getStyleClass().add("disk-table-path");
            path.setMinWidth(0);
            path.setMaxWidth(Double.MAX_VALUE);
            path.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            path.setTooltip(new javafx.scene.control.Tooltip(mountPath));
            HBox.setHgrow(path, Priority.ALWAYS);

            Label capacity = new Label(formatBytes(availableBytes) + "/" + formatBytes(totalBytes));
            capacity.getStyleClass().add("disk-table-capacity");
            capacity.setMinWidth(112);
            capacity.setAlignment(Pos.CENTER_RIGHT);

            Region track = new Region();
            track.getStyleClass().add("disk-table-progress-track");
            track.setMinSize(112, 4);
            track.setPrefSize(112, 4);
            track.setMaxSize(112, 4);
            Region fill = new Region();
            fill.getStyleClass().add("disk-table-progress-fill");
            if (usedPercent >= dangerThreshold) fill.getStyleClass().add("disk-progress-danger");
            else if (usedPercent >= warningThreshold) fill.getStyleClass().add("disk-progress-warning");
            double fillWidth = Math.max(2, 112 * Math.min(100, Math.max(0, usedPercent)) / 100.0);
            fill.setMinSize(fillWidth, 4);
            fill.setPrefSize(fillWidth, 4);
            fill.setMaxSize(fillWidth, 4);
            StackPane progress = new StackPane(track, fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            progress.setMinSize(112, 4);
            progress.setPrefSize(112, 4);
            progress.setMaxSize(112, 4);
            javafx.scene.control.Tooltip.install(progress, new javafx.scene.control.Tooltip(
                    i18nService.get("sysinfo.usedPercent", usedPercent)));

            VBox usage = new VBox(3, capacity, progress);
            usage.setAlignment(Pos.CENTER_RIGHT);
            usage.setMinWidth(112);

            HBox row = new HBox(12, path, usage);
            row.getStyleClass().add("disk-table-row");
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }

        if (rows.getChildren().isEmpty()) {
            floatingContent.getChildren().add(kvRow(i18nService.get("sysinfo.storageDetails"),
                    i18nService.get("sysinfo.noStorageData")));
            return;
        }

        Label pathHeader = new Label(i18nService.get("sysinfo.mountPath"));
        pathHeader.getStyleClass().add("disk-table-header-label");
        pathHeader.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathHeader, Priority.ALWAYS);
        Label capacityHeader = new Label(i18nService.get("sysinfo.availableTotal"));
        capacityHeader.getStyleClass().add("disk-table-header-label");
        capacityHeader.setMinWidth(112);
        capacityHeader.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(12, pathHeader, capacityHeader);
        header.getStyleClass().add("disk-table-header");

        ScrollPane scroll = new ScrollPane(rows);
        scroll.getStyleClass().add("disk-table-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(false);
        scroll.setPrefViewportHeight(Math.min(340, rows.getChildren().size() * 39.0));
        scroll.setMaxHeight(340);

        VBox table = new VBox(header, scroll);
        table.getStyleClass().add("disk-table");
        table.setMaxWidth(Double.MAX_VALUE);
        floatingContent.getChildren().add(table);
    }

    // ── Card UI components ───────────────────────────────────────────────────

    private HBox kvRow(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("card-key");
        Label valLabel = new Label(value);
        valLabel.getStyleClass().add("card-val");
        valLabel.setMinWidth(0);
        valLabel.setMaxWidth(Double.MAX_VALUE);
        valLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        valLabel.setTooltip(new javafx.scene.control.Tooltip(value));
        HBox row = new HBox(8, keyLabel, valLabel);
        row.getStyleClass().add("card-row");
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valLabel, Priority.ALWAYS);
        return row;
    }

    private HBox liveKvRow(String liveKey, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("card-key");
        Label valLabel = new Label(value);
        valLabel.getStyleClass().addAll("card-val", "card-live-val");
        valLabel.setMinWidth(0);
        valLabel.setMaxWidth(Double.MAX_VALUE);
        valLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        floatingLiveLabels.put(liveKey, valLabel);
        HBox row = new HBox(8, keyLabel, valLabel);
        row.getStyleClass().add("card-row");
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valLabel, Priority.ALWAYS);
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

    private Node liveUsageBar(String key, int percent) {
        ProgressBar progress = new ProgressBar();
        progress.getStyleClass().add("resource-usage-progress");
        progress.setMinWidth(0);
        progress.setPrefWidth(300);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setMouseTransparent(true);
        HBox.setHgrow(progress, Priority.ALWAYS);

        Label value = new Label();
        value.getStyleClass().addAll("card-val", "resource-usage-percent");
        value.setMinWidth(50);
        value.setPrefWidth(50);
        value.setMaxWidth(50);
        value.setAlignment(Pos.CENTER_RIGHT);

        LiveUsageBar usageBar = new LiveUsageBar(progress, value);
        floatingUsageBars.put(key, usageBar);
        updateFloatingUsageBar(key, percent);

        HBox row = new HBox(8, progress, value);
        row.getStyleClass().addAll("card-row", "resource-usage-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void updateFloatingUsageBar(String key, int percent) {
        LiveUsageBar usageBar = floatingUsageBars.get(key);
        if (usageBar == null) return;
        int normalized = percent < 0 ? 0 : clampPercent(percent);
        usageBar.progress().setProgress(normalized / 100.0);
        usageBar.value().setText(percent < 0 ? "--" : normalized + "%");
        usageBar.progress().getStyleClass().removeAll("resource-warning", "resource-danger");
        if (percent >= dangerThreshold) {
            usageBar.progress().getStyleClass().add("resource-danger");
        } else if (percent >= warningThreshold) {
            usageBar.progress().getStyleClass().add("resource-warning");
        }
        updateResourceSeverity(usageBar.value(), percent);
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

    public void openFontSettings() {
        Window owner = getScene() != null ? getScene().getWindow() : null;
        Stage stage = owner instanceof Stage ? (Stage) owner : null;
        // 从终端 Tab 打开偏好设置时没有 ConnectionProfileService 上下文，
        // 传 null 让导入 Tab 显示提示信息（用户可从主菜单打开完整偏好设置）
        // 直接选中"终端"Tab。索引由 PreferencesDialog 统一维护，避免新增 Tab 后错位。
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                null, null, null, PreferencesDialog.TAB_TERMINAL);
        FontProfile profile = fontProfileService.activeProfile();
        handles.forEach(h -> h.updateFontProfile(profile));
    }





    // ── Terminal node creation ────────────────────────────────────────────────

    private CompletableFuture<Node> createTerminalNode() {
        FontProfile fontProfile = fontProfileService.activeProfile();
        TerminalViewRequest request = new TerminalViewRequest(
                sshSession.displayName(),
                new ShellRequest("xterm-256color", new TerminalSize(120, 40, 0, 0), null),
                fontProfile,
                themeService.activeColorScheme(),
                terminalRuntimeSettings()
        );
        return terminalViewFactory.createTerminalView(sshSession, request)
                .thenCompose(handle -> {
                    handles.add(handle);
                    // 桥接终端 cwd 属性：当 handle 的 cwd 变化时同步到持久属性
                    ChangeListener<String> cwdListener = (obs, oldCwd, newCwd) -> {
                        if (newCwd != null && !newCwd.isBlank()) {
                            cwdProperty.set(newCwd);
                        }
                    };
                    handle.cwdProperty().addListener(cwdListener);
                    cwdListeners.add(cwdListener);
                    // 注册断连回调：连接丢失时在终端上显示断连提示 + 重连按钮
                    if (handle instanceof DefaultTerminalViewHandle dvh) {
                        dvh.setOnDisconnected(this::onTerminalDisconnected);
                    }
                    // 目录上报由终端工厂在开始渲染之前初始化，避免回显和重复提示符。
                    String initialCwd = handle.cwdProperty().get();
                    if (initialCwd != null && !initialCwd.isBlank()) cwdProperty.set(initialCwd);
                    return FxThread.supplyAsync(() -> createEmbeddedTerminalNode(handle));
                });
    }

    private TerminalRuntimeSettings terminalRuntimeSettings() {
        String raw = appSettingsService.get(
                "terminal.scrollback.lines",
                String.valueOf(TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES));
        try {
            return new TerminalRuntimeSettings(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return TerminalRuntimeSettings.defaults();
        }
    }

    private Node createEmbeddedTerminalNode(TerminalViewHandle handle) {
        if (nativeTerminalHost != null) {
            nativeTerminalHost.attach(handle);
            return new javafx.scene.layout.Pane();
        }
        log.info("Attaching SwingNode terminal component for session {}", sshSession.sessionId());
        javax.swing.JComponent component = handle.component();
        String appearanceLabel = i18nService.get("terminal.appearanceSettings");
        javax.swing.SwingUtilities.invokeLater(() -> com.jlshell.ui.swing.TerminalPopupBridge.install(component,
                appearanceLabel, () -> FxThread.run(this::openFontSettings)));
        SwingNode swingNode = new SwingNode();
        swingNode.setFocusTraversable(true);
        swingNode.getStyleClass().add(TERMINAL_SWING_NODE_STYLE_CLASS);
        swingNode.setCursor(javafx.scene.Cursor.TEXT);
        swingNode.setContent(component);
        primaryFocusListener = (obs, oldFocused, focused) -> {
            if (focused) {
                handle.requestFocus();
            }
        };
        swingNode.focusedProperty().addListener(primaryFocusListener);
        primaryMouseClickHandler = event -> handle.requestFocus();
        swingNode.addEventHandler(MouseEvent.MOUSE_CLICKED, primaryMouseClickHandler);
        HBox.setHgrow(swingNode, Priority.ALWAYS);
        SwingNodeImeBridge.attach(swingNode, handle);
        FxThread.run(handle::requestFocus);
        return swingNode;
    }
}
