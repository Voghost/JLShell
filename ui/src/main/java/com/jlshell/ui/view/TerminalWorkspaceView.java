package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.DefaultTerminalViewHandle;
import com.jlshell.terminal.support.ShellTtyConnector;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
    private final PluginManager pluginManager;
    private final CapabilityBus capabilityBus;
    private final SftpService sftpService;
    private final java.util.function.Function<String, PluginStorage> storageFactory;
    private final java.util.function.Consumer<String> sessionDisconnectListener;
    private final ChangeListener<Number> pluginCatalogRevisionListener;
    /** 本工作区 Tab 对应的会话 id（SSH 会话或合成的 local-uuid），用于 per-session registry 路由 */
    private final String sessionId;
    private final StackPane terminalHost = new StackPane();
    private final List<TerminalViewHandle> handles = new ArrayList<>();
    private final List<ChangeListener<String>> cwdListeners = new ArrayList<>();
    private final Set<String> activatedPluginIds = ConcurrentHashMap.newKeySet();

    private Node primaryNode;
    private ChangeListener<Boolean> primaryFocusListener;
    private EventHandler<MouseEvent> primaryMouseClickHandler;
    private TabPane workspaceTabPane;

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
    private HBox sessionStatusBar;
    private Label ipValueLabel;
    private Label osValueLabel;
    private Label cpuValueLabel;
    private Label memValueLabel;
    private Label diskValueLabel;
    private Timeline statusRefreshTimeline;
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
    private StatusSummary lastStatusSummary;

    // Floating card state
    private javafx.stage.Popup floatingPopup;
    private VBox floatingContent;
    private ScrollPane floatingScroll;
    private String floatingPendingType;
    private HBox floatingAnchor;
    private final Map<String, Label> floatingLiveLabels = new HashMap<>();
    private Button pluginQuickLaunchBtn;
    private HBox toolbar;
    private Region pluginDivider;
    private final List<HBox> pinnedPluginButtons = new ArrayList<>();

    private static final String PINNED_PLUGINS_KEY = "toolbar.pinnedPlugins";
    public static final String STATUS_BAR_VISIBLE_KEY = "ui.terminal.statusBar.visible";
    private static final int MAX_PINNED = 5;
    private static final java.time.Duration STATUS_COMMAND_TIMEOUT = java.time.Duration.ofSeconds(8);

    /** 断连提示覆盖层：显示断连原因 + 重连按钮 */
    private StackPane disconnectOverlay;
    private Label disconnectLabel;
    private Button reconnectBtn;
    private boolean disconnected = false;
    /** 重连回调，由 SessionWorkspaceTab 设置 */
    private Runnable onReconnect;

    public TerminalWorkspaceView(
            String sessionId,
            SshSession sshSession,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            I18nService i18nService,
            ThemeService themeService,
            PluginManager pluginManager,
            CapabilityBus capabilityBus,
            SftpService sftpService,
            java.util.function.Function<String, PluginStorage> storageFactory
    ) {
        this.sessionId = sessionId;
        this.sshSession = sshSession;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.i18nService = i18nService;
        this.themeService = themeService;
        this.pluginManager = pluginManager;
        this.capabilityBus = capabilityBus;
        this.sftpService = sftpService;
        this.storageFactory = storageFactory;
        this.sessionDisconnectListener = reason -> {
            log.warn("[Terminal] SSH disconnect listener fired for session {}, message={}",
                    sshSession.sessionId(), reason);
            FxThread.run(() -> onTerminalDisconnected(ShellTtyConnector.DisconnectReason.IO_ERROR));
        };
        this.sshSession.addDisconnectListener(sessionDisconnectListener);

        getStyleClass().add("workspace-panel");
        setTop(buildToolbar());
        setCenter(terminalHost);
        setBottom(buildSessionStatusBar());
        setStatusBarVisible(Boolean.parseBoolean(appSettingsService.get(STATUS_BAR_VISIBLE_KEY, "true")));
        this.pluginCatalogRevisionListener = (obs, oldRevision, newRevision) -> {
            if (Platform.isFxApplicationThread()) {
                if (toolbar != null) rebuildPinnedPluginButtons();
            } else {
                Platform.runLater(() -> {
                    if (toolbar != null) rebuildPinnedPluginButtons();
                });
            }
        };
        if (pluginManager != null) {
            pluginManager.catalogRevisionProperty().addListener(pluginCatalogRevisionListener);
        }
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

    /**
     * 控制顶部工具栏（插件按钮 + 字体设置）的显隐。
     * 顶栏折叠时隐藏，给终端更多垂直空间。
     */
    public void setToolbarVisible(boolean visible) {
        if (toolbar != null) {
            toolbar.setManaged(visible);
            toolbar.setVisible(visible);
        }
    }

    /** 控制会话底部资源状态栏的显隐，并持久化用户选择。 */
    public void setStatusBarVisible(boolean visible) {
        if (sessionStatusBar != null) {
            sessionStatusBar.setManaged(visible);
            sessionStatusBar.setVisible(visible);
        }
        appSettingsService.set(STATUS_BAR_VISIBLE_KEY, String.valueOf(visible));
        if (visible) {
            startStatusSampling();
        } else {
            stopStatusSampling();
            hideFloatingCard();
        }
    }

    /**
     * 为当前交互式 Shell 安装 OSC 7 目录上报钩子。
     *
     * <p>钩子只存在于本次远程 Shell 进程，不修改用户的 bashrc/zshrc。
     * 发送时由终端连接器过滤命令自身的 PTY 回显，因此不会在终端留下整段内部命令。
     */
    private void installOsc7PromptHook(TerminalViewHandle handle) {
        // bash: PROMPT_COMMAND 末尾可能已有分号（如 "history -a;"），直接追加会
        // 产生 "history -a; ; _jlshell_osc7" 双分号语法错误，因此先 strip 尾部分号再追加。
        String hook = ""
                + ": __JLSHELL_OSC7_SETUP__;"
                + " if [ -n \"$ZSH_VERSION\" ]; then"
                + "   _jlshell_osc7() { printf '\\033]7;file://%s%s\\007' \"$HOSTNAME\" \"$PWD\"; };"
                + "   chpwd_functions=(${chpwd_functions[@]} _jlshell_osc7);"
                + "   _jlshell_osc7;"
                + " elif [ -n \"$BASH_VERSION\" ]; then"
                + "   _jlshell_osc7() { printf '\\033]7;file://%s%s\\007' \"$HOSTNAME\" \"$PWD\"; };"
                + "   _PC=\"${PROMPT_COMMAND%%; }\"; _PC=\"${_PC%% ;}\"; _PC=\"${_PC%%;}\";"
                + "   PROMPT_COMMAND=\"${_PC:+$_PC; }_jlshell_osc7\";"
                + "   _jlshell_osc7;"
                + " fi;"
                + "\n";

        handle.sendStringToTerminalSilently(hook);
        log.debug("[OSC7] Prompt hook installed silently");
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        handles.forEach(handle -> handle.updateColorScheme(scheme));
    }

    public void setWorkspaceTabPane(TabPane tabPane) {
        this.workspaceTabPane = tabPane;
    }

    public CompletableFuture<Void> closeAsync() {
        hideFloatingCard();
        stopStatusSampling();
        sshSession.removeDisconnectListener(sessionDisconnectListener);
        if (pluginManager != null) {
            pluginManager.catalogRevisionProperty().removeListener(pluginCatalogRevisionListener);
        }
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
        pinnedPluginButtons.clear();
        handles.clear();
        cwdListeners.clear();
        primaryNode = null;
        primaryFocusListener = null;
        primaryMouseClickHandler = null;
        disconnectOverlay = null;
        disconnectLabel = null;
        reconnectBtn = null;
        toolbar = null;
        sessionStatusBar = null;
        ipValueLabel = null;
        osValueLabel = null;
        cpuValueLabel = null;
        memValueLabel = null;
        diskValueLabel = null;
        pluginQuickLaunchBtn = null;
        pluginDivider = null;
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
        if (sessionStatusBar != null && sessionStatusBar.isVisible()) {
            startStatusSampling();
        }
    }

    /** 返回终端是否处于断连状态 */
    public boolean isDisconnected() {
        return disconnected;
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        Button fontSettings = iconBtn("/icons/font.svg", i18nService.get("terminal.fontSettings"), this::openFontSettings);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button pluginBtn = iconBtn("/icons/add.svg", i18nService.get("workspace.plugins"), this::showPluginPicker);
        pluginQuickLaunchBtn = pluginBtn;

        pluginDivider = new Region();
        pluginDivider.getStyleClass().add("sysinfo-sep-group");

        toolbar = new HBox(4, pluginDivider);
        rebuildPinnedPluginButtons();
        toolbar.getChildren().addAll(spacer, pluginBtn, fontSettings);
        toolbar.getStyleClass().add("toolbar-strip");
        return toolbar;
    }

    private HBox buildSessionStatusBar() {
        ipValueLabel = statusValueLabel("--");
        osValueLabel = statusValueLabel("--");
        cpuValueLabel = statusValueLabel("--");
        memValueLabel = statusValueLabel("--");
        diskValueLabel = statusValueLabel("--");

        HBox ip = statusSection("/icons/ip.svg", i18nService.get("sysinfo.network"), ipValueLabel, "ip");
        HBox os = statusSection("/icons/system.svg", i18nService.get("sysinfo.os"), osValueLabel, "os");
        HBox cpu = statusSection("/icons/cpu.svg", i18nService.get("sysinfo.cpu"), cpuValueLabel, "cpu");
        HBox mem = statusSection("/icons/memory-solid.svg", i18nService.get("sysinfo.mem"), memValueLabel, "mem");
        HBox disk = statusSection("/icons/folder.svg", i18nService.get("sysinfo.disk"), diskValueLabel, "disk");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button hideButton = iconBtn("/icons/collapse-up.svg", i18nService.get("terminal.statusBar.hide"),
                () -> setStatusBarVisible(false));
        hideButton.getStyleClass().add("terminal-status-hide");

        sessionStatusBar = new HBox(2, ip, makeSep(), os, makeSep(), cpu, makeSep(), mem, makeSep(), disk,
                spacer, hideButton);
        sessionStatusBar.getStyleClass().add("terminal-status-bar");
        return sessionStatusBar;
    }

    private Label statusValueLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("terminal-status-value");
        label.setMinWidth(0);
        label.setMaxWidth(230);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        return label;
    }

    private HBox statusSection(String iconPath, String title, Label value, String type) {
        Region icon = loadSvgShape(iconPath, 12);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("terminal-status-title");
        HBox box = icon == null ? new HBox(5, titleLabel, value) : new HBox(5, icon, titleLabel, value);
        box.setMinWidth(0);
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

    private List<String> loadPinnedPluginIds() {
        String raw = appSettingsService.get(PINNED_PLUGINS_KEY, "");
        if (raw.isBlank()) return Collections.emptyList();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_PINNED)
                .collect(Collectors.toList());
    }

    private void savePinnedPluginIds(List<String> ids) {
        appSettingsService.set(PINNED_PLUGINS_KEY, String.join(",", ids));
    }

    private void rebuildPinnedPluginButtons() {
        // Remove old pinned buttons (between pluginDivider and spacer)
        toolbar.getChildren().removeAll(pinnedPluginButtons);
        pinnedPluginButtons.clear();

        if (pluginManager == null) {
            pluginDivider.setVisible(false);
            pluginDivider.setManaged(false);
            return;
        }

        List<String> pinned = loadPinnedPluginIds();
        List<PluginDescriptor> allPlugins = pluginManager.getAvailablePlugins();

        for (String id : pinned) {
            allPlugins.stream()
                    .filter(d -> d.id().equals(id))
                    .findFirst()
                    .ifPresent(desc -> {
                        JlShellPlugin plugin = desc.instance();
                        Label lbl = new Label(plugin.displayName(i18nService.getLocale()));
                        lbl.getStyleClass().add("sysinfo-label");
                        HBox btn = new HBox(2, loadSvgShape("/icons/plugins.svg", 14), lbl);
                        if (btn.getChildren().get(0) != null) {
                            btn.getChildren().get(0).getStyleClass().add("action-bar-icon");
                        }
                        btn.getStyleClass().add("plugin-pinned-btn");
                        btn.setOnMouseClicked(e -> {
                            e.consume();
                            activatePlugin(desc);
                        });
                        pinnedPluginButtons.add(btn);
                    });
        }

        boolean hasPinned = !pinnedPluginButtons.isEmpty();
        pluginDivider.setVisible(hasPinned);
        pluginDivider.setManaged(hasPinned);

        // Insert after pluginDivider, before spacer
        int insertIdx = toolbar.getChildren().indexOf(pluginDivider) + 1;
        for (int i = 0; i < pinnedPluginButtons.size(); i++) {
            toolbar.getChildren().add(insertIdx + i, pinnedPluginButtons.get(i));
        }
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
        if (primaryNode == null || sessionStatusBar == null || !sessionStatusBar.isVisible() || disconnected) {
            return;
        }
        if (statusRefreshTimeline == null) {
            statusRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(3), event -> refreshStatusSummary()));
            statusRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        if (statusRefreshTimeline.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            resetStatusSamplingBaseline();
            refreshStatusSummary();
            statusRefreshTimeline.play();
        }
    }

    private void stopStatusSampling() {
        statusSamplingGeneration++;
        if (statusRefreshTimeline != null) {
            statusRefreshTimeline.stop();
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
        if (statusRefreshInFlight || disconnected || getScene() == null
                || sessionStatusBar == null || !sessionStatusBar.isVisible()) {
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
                printf 'JL_OS\t%s\n' "$jl_os"
                printf 'JL_HOST\t%s\n' "$jl_host"
                printf 'JL_KERNEL\t%s\n' "$jl_kernel"
                printf 'JL_NET\t%s\t%s\t%s\t%s\n' "$jl_iface" "$jl_ip" "$jl_rx" "$jl_tx"
                printf 'JL_CPU\t%s\t%s\t%s\t%s\n' "$jl_cpu_total" "$jl_cpu_idle" "$jl_load" "$jl_cores"
                printf 'JL_MEM\t%s\t%s\n' "$jl_mem_total" "$jl_mem_used"
                printf 'JL_DISK\t%s\t%s\t%s\n' "$jl_disk_total" "$jl_disk_used" "$jl_disk_pct"
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
                default -> { }
            }
        }
        return new StatusSummary(os, host, kernel, iface, ip, rx, tx, cpuTotal, cpuIdle, load, cores,
                memTotal, memUsed, diskTotal, diskUsed, diskPercent);
    }

    private void applyStatusSummary(StatusSummary summary) {
        lastStatusSummary = summary;
        long now = System.nanoTime();
        if (previousSampleNanos > 0 && now > previousSampleNanos) {
            double elapsedSeconds = (now - previousSampleNanos) / 1_000_000_000.0;
            if (summary.rxBytes() >= previousRxBytes && summary.txBytes() >= previousTxBytes
                    && previousRxBytes >= 0 && previousTxBytes >= 0) {
                currentDownloadRate = formatRate((summary.rxBytes() - previousRxBytes) / elapsedSeconds);
                currentUploadRate = formatRate((summary.txBytes() - previousTxBytes) / elapsedSeconds);
            }
        }
        if (previousCpuTotal >= 0 && summary.cpuTotal() > previousCpuTotal) {
            long totalDelta = summary.cpuTotal() - previousCpuTotal;
            long idleDelta = summary.cpuIdle() - previousCpuIdle;
            int usage = (int) Math.round(100.0 * Math.max(0, totalDelta - idleDelta) / totalDelta);
            currentCpuUsage = Math.max(0, Math.min(100, usage)) + "%";
        } else if (summary.load() >= 0 && summary.cores() > 0) {
            int pressure = (int) Math.round(Math.min(100, summary.load() / summary.cores() * 100));
            currentCpuUsage = pressure + "%";
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
        updateFloatingLiveSummary(summary);
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
            long memTotal, long memUsed, long diskTotal, long diskUsed, int diskPercent
    ) { }

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

        // Loading indicator
        ProgressIndicator loading = new ProgressIndicator();
        loading.setMaxSize(20, 20);

        floatingContent = new VBox(4, loading);
        floatingContent.setAlignment(Pos.CENTER);
        floatingContent.setPadding(new Insets(10));
        floatingContent.setPrefWidth(440);

        floatingScroll = new ScrollPane(floatingContent);
        floatingScroll.getStyleClass().addAll("hover-card", "hover-card-scroll");
        floatingScroll.setFitToWidth(true);
        floatingScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        floatingScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        floatingScroll.setPannable(true);
        floatingScroll.setPrefViewportWidth(440);
        floatingScroll.setPrefViewportHeight(72);
        floatingScroll.setMaxHeight(480);

        floatingPopup = new javafx.stage.Popup();
        floatingPopup.getContent().setAll(floatingScroll);
        floatingPopup.setAutoHide(true);
        floatingPopup.setAutoFix(true);

        Point2D pos = anchor.localToScreen(0, 0);
        floatingPopup.show(anchor, pos.getX(), Math.max(0, pos.getY() - 120));
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
    }

    private void updateFloatingCardViewport() {
        if (floatingScroll == null || floatingContent == null) return;
        floatingContent.applyCss();
        floatingContent.layout();
        double contentHeight = floatingContent.prefHeight(440);
        double maxHeight = 480;
        if (floatingAnchor != null && floatingAnchor.getScene() != null) {
            Point2D anchorPosition = floatingAnchor.localToScreen(0, 0);
            if (anchorPosition != null) {
                javafx.stage.Screen screen = javafx.stage.Screen.getScreensForRectangle(
                                anchorPosition.getX(), anchorPosition.getY(), 1, 1)
                        .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
                maxHeight = Math.min(maxHeight, screen.getVisualBounds().getHeight() * 0.62);
            }
        }
        floatingScroll.setPrefViewportHeight(Math.max(72, Math.min(maxHeight, contentHeight)));
        floatingScroll.setMaxHeight(maxHeight);
        floatingScroll.requestLayout();
    }

    private void positionFloatingCardAboveAnchor() {
        if (floatingPopup == null || !floatingPopup.isShowing() || floatingAnchor == null) return;
        Point2D anchorPosition = floatingAnchor.localToScreen(0, 0);
        if (anchorPosition == null) return;
        javafx.stage.Screen screen = javafx.stage.Screen.getScreensForRectangle(
                        anchorPosition.getX(), anchorPosition.getY(), 1, 1)
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
        javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
        double popupWidth = Math.max(440, floatingPopup.getWidth());
        double popupHeight = Math.max(120, floatingPopup.getHeight());
        double preferredX = anchorPosition.getX();
        if (preferredX + popupWidth > bounds.getMaxX() - 8) {
            preferredX = anchorPosition.getX() + floatingAnchor.getWidth() - popupWidth;
        }
        double x = Math.max(bounds.getMinX() + 8,
                Math.min(preferredX, bounds.getMaxX() - popupWidth - 8));
        double y = Math.max(bounds.getMinY() + 8,
                Math.min(anchorPosition.getY() - popupHeight - 6, bounds.getMaxY() - popupHeight - 8));
        floatingPopup.setX(x);
        floatingPopup.setY(y);
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
                        + "echo \"CPU(s): $(sysctl -n hw.logicalcpu 2>/dev/null)\")";
            case "mem":
                return "free -h 2>/dev/null || (echo \"Total: $(sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1073741824)\" GB\"}')\"; "
                        + "vm_stat 2>/dev/null); "
                        + "echo \"---SWAP---\"; "
                        + "swapon --show 2>/dev/null || echo \"Swap: $(sysctl -n vm.swapusage 2>/dev/null || echo N/A)\"";
            case "disk":
                return "df -Pk 2>/dev/null || df -k 2>/dev/null";
            default:
                return "echo N/A";
        }
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
                case "cpu" -> floatingContent.getChildren().add(liveKvRow("cpu.pressure",
                        i18nService.get("sysinfo.currentPressure"),
                        currentCpuUsage + (lastStatusSummary.load() >= 0
                                ? " · Load " + formatDecimal(lastStatusSummary.load()) : "")));
                case "mem" -> floatingContent.getChildren().add(liveKvRow("mem.usage",
                        i18nService.get("sysinfo.currentUsage"),
                        formatUsage(lastStatusSummary.memUsed(), lastStatusSummary.memTotal())));
                case "disk" -> floatingContent.getChildren().add(liveKvRow("disk.usage",
                        i18nService.get("sysinfo.currentUsage"),
                        lastStatusSummary.diskPercent() + "% · " + formatBytes(lastStatusSummary.diskUsed())
                                + "/" + formatBytes(lastStatusSummary.diskTotal())));
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
        // lscpu: "Model name:            Intel..."
        for (String line : raw.split("\n")) {
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
            if (usedPercent >= 90) fill.getStyleClass().add("disk-progress-danger");
            else if (usedPercent >= 75) fill.getStyleClass().add("disk-progress-warning");
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
        scroll.setPannable(true);
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
        HBox row = new HBox(8, keyLabel, valLabel);
        row.getStyleClass().add("card-row");
        return row;
    }

    private HBox liveKvRow(String liveKey, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("card-key");
        Label valLabel = new Label(value);
        valLabel.getStyleClass().addAll("card-val", "card-live-val");
        floatingLiveLabels.put(liveKey, valLabel);
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
        // 从终端 Tab 打开偏好设置时没有 ConnectionProfileService 上下文，
        // 传 null 让导入 Tab 显示提示信息（用户可从主菜单打开完整偏好设置）
        // 直接选中"终端"Tab。索引由 PreferencesDialog 统一维护，避免新增 Tab 后错位。
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                null, null, null, PreferencesDialog.TAB_TERMINAL);
        FontProfile profile = fontProfileService.activeProfile();
        handles.forEach(h -> h.updateFontProfile(profile));
    }

    // ── Plugin quick launch ─────────────────────────────────────────────────

    private void showPluginPicker() {
        if (pluginManager == null) return;
        List<PluginDescriptor> plugins = pluginManager.getAvailablePlugins();
        if (plugins.isEmpty()) return;

        hideFloatingCard();

        List<String> currentPinned = loadPinnedPluginIds();

        VBox content = new VBox(4);
        content.setPadding(new Insets(8));
        content.setPrefWidth(220);
        content.getStyleClass().add("hover-card");

        Label header = new Label(i18nService.getOrDefault("plugin.pinToToolbar", "Pin to Toolbar"));
        header.getStyleClass().add("card-section");
        content.getChildren().add(header);

        List<CheckBox> boxes = new ArrayList<>();
        for (PluginDescriptor desc : plugins) {
            JlShellPlugin plugin = desc.instance();
            CheckBox cb = new CheckBox(plugin.displayName(i18nService.getLocale()));
            cb.setSelected(currentPinned.contains(desc.id()));
            cb.setUserData(desc.id());
            cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
                long selectedCount = boxes.stream().filter(CheckBox::isSelected).count();
                if (selectedCount > MAX_PINNED) {
                    cb.setSelected(false);
                }
            });
            boxes.add(cb);
            content.getChildren().add(cb);
        }

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.getContent().setAll(content);
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        // Save on close: listen for when popup hides
        popup.setOnHiding(e -> {
            List<String> newPinned = boxes.stream()
                    .filter(CheckBox::isSelected)
                    .map(cb -> (String) cb.getUserData())
                    .limit(MAX_PINNED)
                    .collect(Collectors.toList());
            savePinnedPluginIds(newPinned);
            rebuildPinnedPluginButtons();
        });

        if (pluginQuickLaunchBtn == null) return;
        Point2D pos = pluginQuickLaunchBtn.localToScreen(0, pluginQuickLaunchBtn.getHeight() + 4);
        popup.show(pluginQuickLaunchBtn, pos.getX(), pos.getY());
        floatingPopup = popup;
    }

    private void activatePlugin(PluginDescriptor desc) {
        if (workspaceTabPane == null) return;

        // Prevent duplicate: if already open, just select
        for (Tab tab : workspaceTabPane.getTabs()) {
            String existingId = (String) tab.getProperties().get("pluginId");
            if (existingId != null && existingId.equals(desc.id())) {
                workspaceTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        Optional<SshSessionContext> sshCtx = Optional.of(
                new com.jlshell.plugin.loader.SshSessionContextAdapter(sshSession, sftpService));
        CapabilityRegistryImpl sessionRegistry = pluginManager.registryForSession(sessionId);
        com.jlshell.plugin.api.storage.PluginStorage pluginStorage =
                storageFactory != null ? storageFactory.apply(desc.id()) : null;
        DefaultPluginContext ctx = new DefaultPluginContext(desc.id(), sessionId, sessionRegistry, capabilityBus, pluginStorage, sshCtx, new DefaultPluginContext.Callbacks() {
            private Tab openedTab;

            @Override
            public void openTab(String title, Node content) {
                Platform.runLater(() -> {
                    openedTab = new Tab(title, content);
                    openedTab.setClosable(true);
                    openedTab.getProperties().put("pluginId", desc.id());
                    workspaceTabPane.getTabs().add(openedTab);
                    workspaceTabPane.getSelectionModel().select(openedTab);
                });
            }

            @Override
            public void closeTab() {
                if (openedTab != null) {
                    Platform.runLater(() -> {
                        if (openedTab.getTabPane() != null) {
                            openedTab.getTabPane().getTabs().remove(openedTab);
                        }
                        openedTab = null;
                    });
                }
            }

            @Override
            public void updateTabTitle(String title) {
                if (openedTab != null) {
                    Platform.runLater(() -> openedTab.setText(title));
                }
            }

            @Override
            public String resolveI18n(String key, String fallback) {
                return i18nService.getOrDefault(key, fallback);
            }
        });
        ctx.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
        ctx.writableLocaleProperty().bind(pluginManager.localeProperty());
        pluginManager.adoptContext(sessionId, desc.id(), ctx);
        pluginManager.activatePlugin(desc.id(), ctx);
        activatedPluginIds.add(desc.id());
    }

    public void stopPlugins() {
        activatedPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedPluginIds.clear();
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
                    // 文件页可能稍后才首次创建；连接建立时先静默启用目录上报，
                    // 文件页的复选框只控制是否跟随，不再打断用户弹窗确认。
                    installOsc7PromptHook(handle);
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
        log.info("Attaching SwingNode terminal component for session {}", sshSession.sessionId());
        javax.swing.JComponent component = handle.component();
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
