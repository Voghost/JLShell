package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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
    /** 本工作区 Tab 对应的会话 id（SSH 会话或合成的 local-uuid），用于 per-session registry 路由 */
    private final String sessionId;
    private final StackPane terminalHost = new StackPane();
    private final List<TerminalViewHandle> handles = new ArrayList<>();
    private final List<ChangeListener<String>> cwdListeners = new ArrayList<>();
    private final Set<String> activatedPluginIds = ConcurrentHashMap.newKeySet();

    private Node primaryNode;
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

    // System info bar labels
    private Label ipLabel;
    private Label osLabel;
    private Label cpuLabel;
    private Label memLabel;
    private Label diskLabel;

    // Floating card state
    private javafx.stage.Popup floatingPopup;
    private VBox floatingContent;
    private String floatingPendingType;
    private Button pluginQuickLaunchBtn;
    private HBox toolbar;
    private Region pluginDivider;
    private final List<HBox> pinnedPluginButtons = new ArrayList<>();

    private static final String PINNED_PLUGINS_KEY = "toolbar.pinnedPlugins";
    private static final int MAX_PINNED = 5;

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
        }));
    }

    /**
     * 向终端注入 OSC 7 提示钩子。
     * bash: 通过 PROMPT_COMMAND 在每次提示时发送当前目录
     * zsh: 通过 chpwd 钩子在目录变更时发送
     *
     * <p>使用 stty -echo 临时关闭回显来安静注入，完成后恢复回显。
     */

    /**
     * 控制顶部工具栏（系统信息条 + 插件按钮 + 字体设置）的显隐。
     * 顶栏折叠时隐藏，给终端更多垂直空间。
     */
    public void setToolbarVisible(boolean visible) {
        if (toolbar != null) {
            toolbar.setManaged(visible);
            toolbar.setVisible(visible);
        }
    }

    public void injectOsc7PromptHook() {
        if (handles.isEmpty()) return;
        TerminalViewHandle handle = handles.getFirst();

        // 用 stty -echo 关闭回显 → 执行钩子 → stty echo 恢复回显
        // 用分号连接成单条命令行，一次性发送
        // bash: PROMPT_COMMAND 末尾可能已有分号（如 "history -a;"），直接追加会
        // 产生 "history -a; ; _jlshell_osc7" 双分号语法错误，因此先 strip 尾部分号再追加。
        String hook = ""
                + " stty -echo;"
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
                + " stty echo\n";

        handle.sendStringToTerminal(hook);
        log.debug("[OSC7] Prompt hook injected (quiet mode via stty -echo)");
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        handles.forEach(handle -> handle.updateColorScheme(scheme));
    }

    public void setWorkspaceTabPane(TabPane tabPane) {
        this.workspaceTabPane = tabPane;
    }

    public CompletableFuture<Void> closeAsync() {
        hideFloatingCard();
        if (primaryNode instanceof SwingNode swingNode) {
            swingNode.setContent(null);
        }
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
        terminalHost.getChildren().clear();
        pinnedPluginButtons.clear();
        handles.clear();
        cwdListeners.clear();
        primaryNode = null;
        disconnectOverlay = null;
        disconnectLabel = null;
        reconnectBtn = null;
        toolbar = null;
        pluginQuickLaunchBtn = null;
        pluginDivider = null;
        onReconnect = null;
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
        if (disconnected) return;
        disconnected = true;
        log.warn("[Terminal] Session {} disconnected, reason={}", sshSession.sessionId(), reason);

        String reasonText = switch (reason) {
            case REMOTE_CLOSED -> i18nService.get("terminal.disconnected.remoteClosed");
            case IO_ERROR -> i18nService.get("terminal.disconnected.ioError");
            case USER_CLOSE -> null; // 用户主动关闭不显示提示
        };
        if (reasonText == null) return;

        // SwingNode 直接渲染到窗口 native 层，普通 JavaFX 节点无法覆盖它。
        // 断连时隐藏 SwingNode，让覆盖层可以正常显示。
        if (primaryNode != null) {
            primaryNode.setVisible(false);
            primaryNode.setManaged(false);
        }

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

        terminalHost.getChildren().add(disconnectOverlay);
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
    }

    /** 返回终端是否处于断连状态 */
    public boolean isDisconnected() {
        return disconnected;
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        Button fontSettings = iconBtn("/icons/font.svg", i18nService.get("terminal.fontSettings"), this::openFontSettings);

        ipLabel = new Label(i18nService.get("sysinfo.ip"));
        ipLabel.getStyleClass().add("sysinfo-label");
        osLabel = new Label(i18nService.get("sysinfo.os"));
        osLabel.getStyleClass().add("sysinfo-label");
        cpuLabel = new Label(i18nService.get("sysinfo.cpu"));
        cpuLabel.getStyleClass().add("sysinfo-label");
        memLabel = new Label(i18nService.get("sysinfo.mem"));
        memLabel.getStyleClass().add("sysinfo-label");
        diskLabel = new Label(i18nService.get("sysinfo.disk"));
        diskLabel.getStyleClass().add("sysinfo-label");

        HBox ipSection = sysinfoSection(loadSvgShape("/icons/ip.svg", 12), ipLabel, "ip");
        HBox osSection = sysinfoSection(loadSvgShape("/icons/system.svg", 12), osLabel, "os");
        HBox cpuSection = sysinfoSection(loadSvgShape("/icons/cpu.svg", 12), cpuLabel, "cpu");
        HBox memSection = sysinfoSection(loadSvgShape("/icons/memory-solid.svg", 12), memLabel, "mem");
        HBox diskSection = sysinfoSection(loadSvgShape("/icons/folder.svg", 12), diskLabel, "disk");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button pluginBtn = iconBtn("/icons/add.svg", i18nService.get("workspace.plugins"), this::showPluginPicker);
        pluginQuickLaunchBtn = pluginBtn;

        pluginDivider = new Region();
        pluginDivider.getStyleClass().add("sysinfo-sep-group");

        toolbar = new HBox(4,
                ipSection, makeSep(),
                osSection, makeSep(),
                cpuSection, makeSep(),
                memSection, makeSep(),
                diskSection);
        toolbar.getChildren().addAll(pluginDivider);
        rebuildPinnedPluginButtons();
        toolbar.getChildren().addAll(spacer, pluginBtn, fontSettings);
        toolbar.getStyleClass().add("toolbar-strip");
        return toolbar;
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
        // 从终端 Tab 打开偏好设置时没有 ConnectionProfileService 上下文，
        // 传 null 让导入 Tab 显示提示信息（用户可从主菜单打开完整偏好设置）
        // initialTabIndex=2 → 直接选中"终端"Tab（0=通用, 1=连接, 2=终端）
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                null, null, null, 2);
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
