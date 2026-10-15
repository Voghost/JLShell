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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path filesHome;
    @BeforeAll
    static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }

    private static java.awt.Component named(java.awt.Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof java.awt.Container container) for (java.awt.Component child : container.getComponents()) {
            var found = named(child, name); if (found != null) return found;
        }
        return null;
    }

    private static void snapshot(SwingWorkbench workbench) {
        String path = System.getProperty("jlshell.ui.snapshot");
        snapshot(workbench, path);
    }

    private static void snapshot(SwingWorkbench workbench, String path) {
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
        AtomicInteger detailRequests = new AtomicInteger();
        when(session.execute(any())).thenAnswer(call -> {
            CommandRequest request = call.getArgument(0);
            boolean summary = request.command().contains("JL_CPU");
            if (request.command().contains("lscpu")) detailRequests.incrementAndGet();
            String output = summary ? "JL_OS\tDebian GNU/Linux 13\nJL_HOST\tsrv-fixture\nJL_KERNEL\tLinux 6.12\n"
                    + "JL_CPU\t10000\t5000\t1.84\t8\nJL_MEM\t13529146982\t3221225472\n"
                    + "JL_DISK\t121332826112\t25769803776\t23\nJL_NET\teth0\t192.0.2.1\t1000000\t500000\n"
                    + "JL_TIME\t1791968400\tCST\t+0800\n" : "CPU(s): 8\nModel name: Fixture CPU\n---PROCESSES---\n42 root 12.5 3.1 1024 /usr/bin/java fixture\n";
            return CompletableFuture.completedFuture(new CommandResult(request.command(), 0, output, "", Duration.ZERO));
        });
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
        var plugins = mock(com.jlshell.plugin.loader.PluginManager.class);
        var pluginInstance = mock(com.jlshell.plugin.api.JlShellPlugin.class);
        when(pluginInstance.displayName(any())).thenReturn("系统监控");
        when(pluginInstance.description(any())).thenReturn("实时 CPU、内存、网络和磁盘监控，支持趋势图和详细资源信息，窄栏内也应完整换行显示。");
        var descriptor = new com.jlshell.plugin.loader.PluginDescriptor("fixture", "Monitor", "1", "Fixture", "", true,
                null, null, null, null, pluginInstance);
        when(plugins.getAvailablePlugins()).thenReturn(List.of(descriptor));
        when(plugins.catalogRevisionProperty()).thenReturn(new javafx.beans.property.SimpleLongProperty());
        when(plugins.themeNameProperty()).thenReturn(new SimpleStringProperty("Dark"));
        when(plugins.localeProperty()).thenReturn(new javafx.beans.property.SimpleObjectProperty<>(Locale.SIMPLIFIED_CHINESE));
        when(plugins.registryForSession(anyString())).thenReturn(new com.jlshell.plugin.loader.CapabilityRegistryImpl());
        AtomicInteger nativeCloses = new AtomicInteger();
        doAnswer(call -> {
            com.jlshell.plugin.api.PluginContext pluginContext = call.getArgument(1);
            assertTrue(pluginContext.supportsSwingViews());
            pluginContext.openSwingTab("系统监控", () -> new NativePluginFixture(nativeCloses));
            return null;
        }).when(plugins).activatePlugin(anyString(), any());
        SftpService filesService = mock(SftpService.class);
        when(filesService.listDirectory(eq(session), anyString())).thenReturn(CompletableFuture.completedFuture(
                new com.jlshell.sftp.model.RemoteDirectoryListing("/home/fixture", List.of())));
        SessionWorkspaceTab source = FxThread.supplyAsync(() -> new SessionWorkspaceTab(profile, "fixture", session,
                sessions, mock(ConnectionProfileService.class), factory, fonts, settings, filesService,
                i18n, theme, plugins, null, null, null, null, null, () -> {})).get(10, TimeUnit.SECONDS);
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
                var rightSplit = (javax.swing.JSplitPane) named(workbench.frame(), "workspace.rightSplit");
                assertNull(rightSplit.getRightComponent(), "Unopened tools must not create an empty right pane");
                assertEquals(0, rightSplit.getDividerSize());
                assertFalse(rightSplit.isEnabled(), "Collapsed right dock must not be draggable");
                snapshot(workbench);
                assertTrue(SwingUtilities.isDescendingFrom(widget, nativeView));
                assertFalse(SwingUtilities.isDescendingFrom(widget, nativeView.toolWindow()));
                assertNotNull(named(nativeView.toolWindow(), "monitor.native"));
                assertTrue(NativeSettingsProbe.all(nativeView.toolWindow(), javafx.embed.swing.JFXPanel.class).isEmpty(),
                        "Builtin server monitoring must be entirely Swing");
                workbench.toggleToolWindow();
                assertTrue(widget.isShowing());
                workbench.toggleToolWindow();
                assertTrue(widget.isShowing());
            }).get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                boolean ready = SwingExecutors.supplyOnEdtAsync(() -> ((javax.swing.JLabel) named(nativeView.toolWindow(), "monitor.host")).getText().contains("srv-fixture")).get(10, TimeUnit.SECONDS);
                if (ready) break;
                Thread.sleep(50);
            }
            int defaultDivider = SwingExecutors.supplyOnEdtAsync(() -> {
                var label = named(workbench.frame(), "monitor.connectionLabel");
                assertInstanceOf(javax.swing.JLabel.class, label);
                assertEquals("SSH UI fixture", ((javax.swing.JLabel) label).getText());
                assertInstanceOf(javax.swing.border.EmptyBorder.class, ((javax.swing.JLabel) label).getBorder());
                var split = (javax.swing.JSplitPane) named(workbench.frame(), "workspace.verticalSplit");
                assertTrue(split.getBottomComponent().getHeight() <= 180, "Monitor must default to a compact dock");
                snapshot(workbench, "../ui/target/review/server-monitor-workspace-default.png");
                int previous = split.getDividerLocation();
                split.setDividerLocation(split.getMaximumDividerLocation());
                workbench.frame().validate();
                return previous;
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                workbench.frame().validate();
                var dock = named(workbench.frame(), "monitor.dock");
                assertTrue(dock.getHeight() >= 96, "Dragging must respect the minimum dock height");
                for (String type : List.of("cpu", "mem", "ip", "disk")) {
                    var tile = (javax.swing.JComponent) named(nativeView.toolWindow(), "monitor." + type);
                    assertEquals(false, tile.getClientProperty("monitor.chartsVisible"));
                    for (var label : NativeSettingsProbe.all(tile, javax.swing.JLabel.class))
                        assertTrue(label.getHeight() >= label.getFontMetrics(label.getFont()).getHeight(), "Minimum-height monitor must not clip text");
                }
                assertTrue(NativeSettingsProbe.all(nativeView.toolWindow(), javax.swing.JScrollPane.class).stream()
                        .noneMatch(scroll -> scroll.getVerticalScrollBar().isVisible()), "Minimum dock should fit without a scrollbar");
                snapshot(workbench, "../ui/target/review/server-monitor-workspace-minimum.png");
                ((javax.swing.JSplitPane) named(workbench.frame(), "workspace.verticalSplit")).setDividerLocation(defaultDivider);
                workbench.frame().validate();
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                assertTrue(((javax.swing.JLabel) named(nativeView.toolWindow(), "monitor.host")).getText().contains("srv-fixture"));
                ((javax.swing.JButton) named(nativeView.toolWindow(), "monitor.cpu")).doClick();
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                assertTrue(detailRequests.get() > 0, "Native detail actions must reuse SSH detail commands");
                var path = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
                var popup = java.util.Arrays.stream(path).map(javax.swing.MenuElement::getComponent)
                        .filter(c -> "monitor.details".equals(c.getName())).findFirst().orElseThrow();
                var table = (javax.swing.JTable) named(popup, "monitor.processes");
                assertEquals(1, table.getRowCount());
                assertEquals("42", table.getValueAt(0, 0));
                assertEquals("/usr/bin/java fixture", table.getValueAt(0, 5));
                var image = new java.awt.image.BufferedImage(popup.getWidth()*2, popup.getHeight()*2, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); graphics.scale(2,2); popup.printAll(graphics); graphics.dispose();
                try { javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of("../ui/target/review/server-monitor-cpu-details.png").toFile()); }
                catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            }).get(10, TimeUnit.SECONDS);
            String previousHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", filesHome.toString());
                FxThread.supplyAsync(() -> { source.getInnerTabPane().getSelectionModel().select(1); return null; }).get(10, TimeUnit.SECONDS);
                SwingExecutors.runOnEdtAsync(() -> {
                    var files = named(workbench.frame(), "sftp.browser");
                    assertNotNull(files, "File rail must open the native browser");
                    assertTrue(files.isShowing());
                    var rightSplit = (javax.swing.JSplitPane) named(workbench.frame(), "workspace.rightSplit");
                    assertNotNull(rightSplit.getRightComponent(), "Opening files must mount the resizable right pane");
                    assertEquals(4, rightSplit.getDividerSize());
                    assertTrue(rightSplit.isEnabled());
                    assertTrue(NativeSettingsProbe.all(files, javafx.embed.swing.JFXPanel.class).isEmpty());
                    assertTrue(widget.isShowing());
                    workbench.frame().validate();
                }).get(10, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 100; attempt++) {
                    boolean ready = SwingExecutors.supplyOnEdtAsync(() -> {
                        workbench.frame().validate();
                        var files = named(workbench.frame(), "sftp.browser");
                        return files != null && files.getWidth() > 250 && files.getHeight() > 250;
                    }).get(10, TimeUnit.SECONDS);
                    if (ready) break;
                    Thread.sleep(25);
                }
                SwingExecutors.runOnEdtAsync(() -> {
                    var files = named(workbench.frame(), "sftp.browser");
                    assertTrue(files.getWidth() > 250 && files.getHeight() > 250, "Native files must receive real layout bounds");
                    snapshot(workbench, "../ui/target/review/sftp-workspace-dark.png");
                }).get(10, TimeUnit.SECONDS);
            } finally { System.setProperty("user.home", previousHome); }
            FxThread.supplyAsync(() -> { source.getInnerTabPane().getSelectionModel().select(2); return null; }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> workbench.frame().validate()).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                var list = named(workbench.frame(), "session.plugins"); assertNotNull(list);
                assertTrue(NativeSettingsProbe.all(list, javafx.embed.swing.JFXPanel.class).isEmpty());
                var card = named(list, "plugin.session:fixture");
                assertTrue(card.getWidth() <= list.getWidth()); assertTrue(card.getHeight() >= 130);
                snapshot(workbench, "../ui/target/review/plugins-sidebar-dark.png");
                ((javax.swing.JToggleButton)named(list, "plugin-pin-session:fixture")).doClick();
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            verify(settings).set("ui.pluginSidebar.pinned.session:fixture", "true");
            SwingExecutors.runOnEdtAsync(() -> {
                var shortcut = named(workbench.frame(), "plugin-shortcut-session:fixture");
                assertNotNull(shortcut,"Pinning a plugin must add its own rail icon before it is opened");
                assertEquals("系统监控",((javax.swing.JToggleButton)shortcut).getToolTipText());
                assertTrue(named(workbench.frame(),"right.toolWindow").getWidth()<=330);
                snapshot(workbench);
                ((javax.swing.AbstractButton)shortcut).doClick();
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                var page = named(workbench.frame(), "fixture.native.plugin"); assertNotNull(page);
                assertTrue(NativeSettingsProbe.all(page, javafx.embed.swing.JFXPanel.class).isEmpty());
                ((javax.swing.AbstractButton) named(workbench.frame(), "plugin-shortcut-session:fixture")).doClick();
                var rightSplit = (javax.swing.JSplitPane) named(workbench.frame(), "workspace.rightSplit");
                assertNull(rightSplit.getRightComponent(), "Closing the active tool must remove its splitter pane");
                assertEquals(0, rightSplit.getDividerSize());
                assertFalse(rightSplit.isEnabled());
                workbench.frame().setSize(1140, 800);
                workbench.frame().validate();
            }).get(10, TimeUnit.SECONDS);
            assertTrue(FxThread.supplyAsync(() -> source.getInnerTabPane().getTabs().stream()
                    .anyMatch(tab -> "session:fixture".equals(tab.getProperties().get("pluginId")))).get(10, TimeUnit.SECONDS));
            SwingExecutors.runOnEdtAsync(() -> {
                var rightSplit = (javax.swing.JSplitPane) named(workbench.frame(), "workspace.rightSplit");
                assertNull(rightSplit.getRightComponent(), "Resizing while tools are closed must not restore an empty sidebar");
                assertEquals(0, rightSplit.getDividerSize());
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> { source.getInnerTabPane().getSelectionModel().select(2); return null; }).get(10,TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> ((javax.swing.JToggleButton)named(workbench.frame(), "plugin-pin-session:fixture")).doClick()).get(10,TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10,TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> assertNull(named(workbench.frame(),"plugin-shortcut-session:fixture"))).get(10,TimeUnit.SECONDS);
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
            SwingExecutors.runOnEdtAsync(() -> {
                assertEquals(1, NativeSettingsProbe.all(workbench.frame(), javafx.embed.swing.JFXPanel.class).size(),
                        "Legacy plugins must retain their original JavaFX controls");
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> {
                assertNotNull(plugin.getContent().getScene(), "Legacy plugin pages must attach an FX Scene");
                assertEquals("-fx-padding: 19px;", plugin.getContent().getStyle());
                ((Button) ((VBox) plugin.getContent()).getChildren().getFirst()).fire();

                source.getInnerTabPane().getSelectionModel().select(0);
                source.getInnerTabPane().getTabs().remove(plugin);
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, clicks.get());
            SwingExecutors.runOnEdtAsync(() -> {
                assertTrue(widget.isShowing()); nativeView.close(); nativeView.close();
                assertEquals(1,nativeCloses.get(),"Closing the session must release the native plugin once");
            }).get(10, TimeUnit.SECONDS);
        } finally {
            FxThread.supplyAsync(source::closeWorkspace).get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> { workbench.close(); widget.close(); }).get(10, TimeUnit.SECONDS);
            executor.shutdownNow();

        }
    }
    private static final class NativePluginFixture extends javax.swing.JPanel implements AutoCloseable {
        private final AtomicInteger closes;
        NativePluginFixture(AtomicInteger closes) {
            this.closes = closes; setName("fixture.native.plugin");
            add(new javax.swing.JLabel("Native Swing plugin"));
        }
        public void close() { closes.incrementAndGet(); }
    }

}
