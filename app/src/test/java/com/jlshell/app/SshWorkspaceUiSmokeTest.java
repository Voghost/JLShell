package com.jlshell.app;

import com.jlshell.core.model.*;
import com.jlshell.core.service.*;
import com.jlshell.core.session.SshSession;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.*;
import com.jlshell.terminal.support.*;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.swing.*;
import com.jlshell.ui.theme.*;
import com.jlshell.ui.view.SessionWorkspaceTab;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 SSH 会话 UI 初始化路径，SSH/文件服务在测试中替代，不访问远端或用户凭据。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SshWorkspaceUiSmokeTest {
    @BeforeAll
    static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(10, TimeUnit.SECONDS);
    }

    private static void snapshot(SwingWorkbench workbench) {
        String path = System.getProperty("jlshell.ui.snapshot");
        if (path == null) return;
        var root = workbench.frame().getRootPane();
        var image = new java.awt.image.BufferedImage(root.getWidth(), root.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            root.printAll(graphics);
            java.nio.file.Path target = java.nio.file.Path.of(path);
            java.nio.file.Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(image, "png", target.toFile());
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        finally { graphics.dispose(); }
    }

    @Test
    void showsSshTerminalInMainWorkspaceWhileAuxiliaryPagesChange() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SwingExecutors.runOnEdtAsync(() -> SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13))
                .get(10, TimeUnit.SECONDS);
        var settings = mock(AppSettingsService.class);
        when(settings.get(anyString())).thenReturn(Optional.empty());
        when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        var fonts = mock(FontProfileService.class);
        var font = new FontProfile("Monospaced", 13, false, 1);
        when(fonts.activeProfile()).thenReturn(font);
        when(fonts.listAvailableProfiles()).thenReturn(List.of(font));
        var registry = mock(ColorSchemeRegistry.class);
        when(registry.findByName(anyString())).thenReturn(Optional.empty());
        ThemeService theme = FxThread.supplyAsync(() -> new ThemeService(settings, registry, executor)).get(10, TimeUnit.SECONDS);
        var session = mock(SshSession.class);
        SessionId sessionId = SessionId.randomId();
        when(session.sessionId()).thenReturn(sessionId);
        when(session.displayName()).thenReturn("SSH UI fixture");
        when(session.target()).thenReturn(new ConnectionTarget("192.0.2.1", 22, "fixture", null, null));
        when(session.state()).thenReturn(SessionState.CONNECTED);
        when(session.connectedAt()).thenReturn(Instant.now());
        when(session.execute(any())).thenReturn(CompletableFuture.completedFuture(
                new CommandResult("fixture", 0, "", "", Duration.ZERO)));
        var widget = SwingExecutors.supplyOnEdtAsync(() -> JlshellJediTermWidget.create(120, 40,
                new JlshellSettingsProvider(font, TerminalColorScheme.dark()), key -> key)).get(10, TimeUnit.SECONDS);
        var handle = mock(TerminalViewHandle.class);
        when(handle.component()).thenReturn(widget);
        when(handle.cwdProperty()).thenReturn(new SimpleStringProperty(""));
        when(handle.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        var factory = mock(TerminalViewFactory.class);
        when(factory.createTerminalView(eq(session), any())).thenReturn(CompletableFuture.completedFuture(handle));
        var sessions = mock(SessionManager.class);
        when(sessions.closeSession(sessionId)).thenReturn(CompletableFuture.completedFuture(null));
        var profile = new ConnectionProfile("fixture", "SSH UI fixture", "192.0.2.1", 22, "fixture",
                AuthenticationType.PASSWORD, HostKeyVerificationMode.STRICT, null, null, false, null,
                ConnectionType.SSH, "fixture-folder", null);
        var i18n = new I18nService(Locale.SIMPLIFIED_CHINESE);
        SessionWorkspaceTab source = FxThread.supplyAsync(() -> new SessionWorkspaceTab(profile, "fixture", session,
                sessions, mock(ConnectionProfileService.class), factory, fonts, settings, mock(SftpService.class),
                i18n, theme, null, null, null, null, null, null, () -> {})).get(10, TimeUnit.SECONDS);
        var nativeView = FxThread.supplyAsync(() -> SwingSessionView.create(source, theme, i18n))
                .get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        SwingWorkbench workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var texts = SwingWorkbench.TEXT_KEYS.stream().collect(java.util.stream.Collectors.toMap(key -> key, i18n::get));
            var view = new SwingWorkbench(actions, texts);
            view.updateConnections(List.of(new com.jlshell.ui.model.FolderProfile("fixture-folder", "开发环境", null, null, 0)),
                    List.of(profile), java.util.Set.of());
            view.frame().setSize(1100, 800);
            view.addTab(source, "SSH UI fixture", nativeView, noop);
            view.show();
            return view;
        }).get(10, TimeUnit.SECONDS);
        try {
            FxThread.supplyAsync(source::initialize).get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                workbench.frame().validate();
                widget.getTerminal().writeCharacters("SSH TERMINAL VISIBLE");
                assertTrue(widget.isShowing());
                assertTrue(widget.getWidth() > 500 && widget.getHeight() > 100, () -> widget.getSize().toString());
                snapshot(workbench);
                assertTrue(SwingUtilities.isDescendingFrom(widget, nativeView));
                assertFalse(SwingUtilities.isDescendingFrom(widget, nativeView.toolWindow()));
                workbench.toggleToolWindow();
                assertTrue(widget.isShowing());
                workbench.toggleToolWindow();
                assertTrue(widget.isShowing());
            }).get(10, TimeUnit.SECONDS);
            AtomicInteger clicks = new AtomicInteger();
            Tab plugin = FxThread.supplyAsync(() -> {
                Button button = new Button("原插件动作");
                button.setOnAction(event -> clicks.incrementAndGet());
                VBox content = new VBox(button);
                content.setStyle("-fx-padding: 19px;");
                Tab tab = new Tab("插件页面", content);
                source.getInnerTabPane().getTabs().add(tab);
                source.getInnerTabPane().getSelectionModel().select(tab);
                return tab;
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                assertTrue(widget.isShowing());
                assertTrue(widget.getTerminalTextBuffer().getScreenLines().contains("SSH TERMINAL VISIBLE"));
                assertSame(workbench.frame(), SwingUtilities.getWindowAncestor(nativeView.toolWindow()));
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> {
                assertNotNull(plugin.getContent().getScene());
                assertEquals("-fx-padding: 19px;", plugin.getContent().getStyle());
                ((Button) ((VBox) plugin.getContent()).getChildren().getFirst()).fire();
                source.getInnerTabPane().getSelectionModel().select(0);
                source.getInnerTabPane().getTabs().remove(plugin);
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, clicks.get());
            SwingExecutors.runOnEdtAsync(() -> assertTrue(widget.isShowing())).get(10, TimeUnit.SECONDS);
        } finally {
            FxThread.supplyAsync(source::closeWorkspace).get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> { workbench.close(); widget.close(); }).get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }
}
