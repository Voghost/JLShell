package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 终端工作区，支持单视图和基础分屏。
 */
public class TerminalWorkspaceView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(TerminalWorkspaceView.class);

    private final SshSession sshSession;
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;
    private final I18nService i18nService;
    private final StackPane terminalHost = new StackPane();
    private final List<TerminalViewHandle> handles = new ArrayList<>();

    private AppTheme appTheme;
    private Node primaryNode;
    private Node secondaryNode;
    private SplitPane splitPane;

    public TerminalWorkspaceView(
            SshSession sshSession,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            I18nService i18nService,
            AppTheme appTheme
    ) {
        this.sshSession = sshSession;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.i18nService = i18nService;
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
        }));
    }

    public void applyTheme(AppTheme theme) {
        this.appTheme = theme;
        handles.forEach(handle -> handle.updateColorScheme(theme.terminalColorScheme()));
    }

    public CompletableFuture<Void> closeAsync() {
        return CompletableFuture.allOf(
                handles.stream()
                        .map(TerminalViewHandle::closeAsync)
                        .toArray(CompletableFuture[]::new)
        );
    }

    private static final String ICON_SPLIT_V  = "M3 3h8v18H3zM13 3h8v18h-8z";
    private static final String ICON_SPLIT_H  = "M3 3h18v8H3zM3 13h18v8H3z";
    private static final String ICON_RESET    = "M3 3h18v18H3zM10 5h4v14h-4z";
    private static final String ICON_FONT     = "M5 4h14v3h-1V6h-5v13h2v1H9v-1h2V6H6v1H5z";

    private HBox buildToolbar() {
        Button verticalSplit   = iconBtn(ICON_SPLIT_V, i18nService.get("terminal.splitVertical"),   () -> split(Orientation.HORIZONTAL));
        Button horizontalSplit = iconBtn(ICON_SPLIT_H, i18nService.get("terminal.splitHorizontal"), () -> split(Orientation.VERTICAL));
        Button resetLayout     = iconBtn(ICON_RESET,   i18nService.get("terminal.resetSplit"),      this::resetLayout);
        Button fontSettings    = iconBtn(ICON_FONT,    i18nService.get("terminal.fontSettings"),    this::openFontSettings);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(4, verticalSplit, horizontalSplit, resetLayout, spacer, fontSettings);
        toolbar.getStyleClass().add("toolbar-strip");
        return toolbar;
    }

    private Button iconBtn(String svgPath, String tooltip, Runnable action) {
        Region icon = new Region();
        icon.getStyleClass().add("action-bar-icon");
        icon.setStyle("-fx-shape: \"" + svgPath + "\"; -fx-pref-width: 14; -fx-pref-height: 14; "
                     + "-fx-min-width: 14; -fx-min-height: 14; -fx-max-width: 14; -fx-max-height: 14; "
                     + "-fx-scale-shape: true;");
        Button btn = new Button();
        btn.setGraphic(icon);
        btn.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        btn.getStyleClass().add("icon-btn");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private void openFontSettings() {
        Window owner = getScene() != null ? getScene().getWindow() : null;
        javafx.stage.Stage stage = owner instanceof javafx.stage.Stage ? (javafx.stage.Stage) owner : null;
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService);
        // Apply the (possibly updated) active profile to all open terminals
        FontProfile profile = fontProfileService.activeProfile();
        handles.forEach(h -> h.updateFontProfile(profile));
    }

    private void split(Orientation orientation) {
        if (primaryNode == null) {
            return;
        }
        createTerminalNode().whenComplete((node, throwable) -> FxThread.run(() -> {
            if (throwable != null) {
                return;
            }
            secondaryNode = node;
            splitPane = new SplitPane(primaryNode, secondaryNode);
            splitPane.setOrientation(orientation);
            splitPane.setDividerPositions(0.5);
            terminalHost.getChildren().setAll(splitPane);
        }));
    }

    private void resetLayout() {
        if (primaryNode != null) {
            terminalHost.getChildren().setAll(primaryNode);
        }
    }

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
                    // 统一走嵌入模式，依赖 -Djavafx.embed.singleThread=true 避免 macOS 死锁
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
