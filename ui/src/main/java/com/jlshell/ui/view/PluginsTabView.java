package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.loader.SshSessionContextAdapter;
import com.jlshell.program.plugin.loader.ProgramSessionIntegrationRegistry;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.model.WorkspacePage;
import com.jlshell.ui.model.WorkspaceTabs;
import com.jlshell.core.support.MutableValue;
import javax.swing.SwingUtilities;

/** Lists Session plugins and session surfaces contributed by Program plugins. */
public class PluginsTabView {

    private final I18nService i18nService;
    private final PluginManager pluginManager;
    private final ProgramSessionIntegrationRegistry programSessionRegistry;
    private final CapabilityBus capabilityBus;
    private final java.util.function.Function<String, PluginStorage> storageFactory;
    private final String sessionId;
    private final SshSessionContext sshContext;
    private final WorkspaceTabs workspaceTabs;
    private final Set<String> activatedSessionPluginIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final com.jlshell.plugin.api.lifecycle.Registration localeSubscription;
    private final com.jlshell.plugin.api.lifecycle.Registration catalogSubscription;
    private final com.jlshell.plugin.api.lifecycle.Registration programCatalogSubscription;
    public static final String NATIVE_VIEW = "jlshell.plugin.swingView";
    private final java.util.concurrent.atomic.AtomicReference<com.jlshell.ui.swing.SwingPluginList> nativeView = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean disposed;
    private volatile long activationGeneration;
    private final Set<String> opening = new java.util.HashSet<>();
    private java.util.concurrent.CompletableFuture<Void> activation = java.util.concurrent.CompletableFuture.completedFuture(null);
    public static final String PINNED_SHORTCUTS = "jlshell.pinnedPluginShortcuts";
    public record PinnedShortcut(String id, String title, Runnable open, com.jlshell.ui.swing.PluginIcons.Source icon) {}
    private final MutableValue<List<PinnedShortcut>> pinnedShortcuts;
    private final com.jlshell.core.service.AppSettingsService settings;
    private final Set<String> pinnedIds = new java.util.HashSet<>();
    private List<LaunchItem> availableItems = List.of();

    public PluginsTabView(
            PluginManager pluginManager,
            ProgramSessionIntegrationRegistry programSessionRegistry,
            String sessionId,
            SshSession sshSession,
            WorkspaceTabs workspaceTabs,
            I18nService i18nService,
            ThemeService themeService,
            SftpService sftpService,
            CapabilityBus capabilityBus,
            java.util.function.Function<String, PluginStorage> storageFactory,
            com.jlshell.core.service.AppSettingsService settings, MutableValue<List<PinnedShortcut>> pinnedShortcuts
    ) {
        this.i18nService = i18nService;
        this.settings = settings;
        this.pinnedShortcuts = pinnedShortcuts;
        this.pluginManager = pluginManager;
        this.programSessionRegistry = programSessionRegistry;
        this.sessionId = sessionId;
        this.workspaceTabs = workspaceTabs;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.sshContext = new SshSessionContextAdapter(sshSession, sftpService);

        localeSubscription = i18nService.onLocaleChanged(newLocale ->
                SwingUtilities.invokeLater(() -> {
                    if (!disposed) { publishPinnedShortcuts(); }
                }));
        refreshPluginList();
        catalogSubscription = pluginManager.catalogRevisionProperty().subscribe(revision -> refreshOnUiThread());
        programCatalogSubscription = programSessionRegistry == null
                ? com.jlshell.plugin.api.lifecycle.Registration.noop()
                : programSessionRegistry.revisionProperty().subscribe(revision -> refreshOnUiThread());
    }

    private void requestOpen(LaunchItem item) {
        var timing = new com.jlshell.ui.support.PluginOpenTiming(item.tabKey());
        SwingUtilities.invokeLater(() -> {
            timing.mark("edt-dispatch");
            if (disposed) { timing.finish("disposed"); return; }
            for (WorkspacePage page : workspaceTabs.pages()) {
                if (item.tabKey().equals(page.getProperties().get("pluginId"))) {
                    workspaceTabs.select(page); timing.finish("reused-tab"); return;
                }
            }
            if (!opening.add(item.tabKey())) { timing.finish("already-opening"); return; }
            long generation = activationGeneration;
            activation = activation.handle((ignored, error) -> null).thenRunAsync(() -> {
                if (disposed || generation != activationGeneration) { timing.finish("disposed"); return; }
                try { activate(item, timing, generation); }
                finally { if (disposed || generation != activationGeneration) deactivateAfterUserClose(item); }
            }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                opening.remove(item.tabKey());
                if (error != null) {
                    timing.failed(error);
                    org.slf4j.LoggerFactory.getLogger(PluginsTabView.class).warn("Cannot open session plugin {}", item.tabKey(), error);
                }
            }));
        });
    }

    /** 当前会话可用的插件入口，供主菜单生成。 */
    public List<PluginMenuItem> createMenuItems() {
        return availableItems.stream().map(item -> new PluginMenuItem(item.tabKey(), item.displayName(i18nService.getLocale()), () -> requestOpen(item))).toList();
    }

    public record PluginMenuItem(String id, String title, Runnable open) {}

    private void activate(LaunchItem item, com.jlshell.ui.support.PluginOpenTiming timing, long generation) {
        try (var activation = timing.step("activation-and-context")) {
            try {
                CapabilityRegistryImpl sessionRegistry = pluginManager.registryForSession(sessionId);
                PluginStorage storage = storageFactory == null ? null : storageFactory.apply(item.ownerPluginId());
                DefaultPluginContext context = new DefaultPluginContext(
                        item.ownerPluginId(), sessionId, sessionRegistry, capabilityBus, storage,
                        Optional.of(sshContext), callbacks(item, timing, generation));
                context.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
                context.writableLocaleProperty().bind(pluginManager.localeProperty());
                timing.mark("context-ready");

                if (item.sessionPlugin() != null) {
                    pluginManager.adoptContext(sessionId, item.ownerPluginId(), context);
                    pluginManager.activatePlugin(item.ownerPluginId(), context, timing.trace());
                    activatedSessionPluginIds.add(item.ownerPluginId());
                } else if (programSessionRegistry != null) {
                    timing.trace().call("program-session-plugin-code", () ->
                            programSessionRegistry.activate(item.ownerPluginId(), sessionId, context));
                }
                timing.mark("activation-returned");
            } catch (RuntimeException | Error error) {
                activation.failed(error);
                timing.failed(error);
                throw error;
            }
        }
    }

    private DefaultPluginContext.Callbacks callbacks(LaunchItem item, com.jlshell.ui.support.PluginOpenTiming timing, long generation) {
        return new DefaultPluginContext.Callbacks() {
            private WorkspacePage openedTab;

            @Override public boolean supportsSwingViews() { return true; }

            @Override public void openSwingTab(String title, java.util.function.Supplier<javax.swing.JComponent> content) {
                timing.mark("swing-view-request");
                // 在 EDT 发布页面，插件后台初始化完成前即可创建并绘制界面。
                SwingUtilities.invokeLater(() -> {
                    if (disposed || generation != activationGeneration) { timing.finish("disposed"); return; }
                    openedTab = new WorkspacePage(title);
                    openedTab.setContent(content);
                    openedTab.getProperties().put("pluginId", item.tabKey());
                    openedTab.getProperties().put(NATIVE_VIEW, content);
                    openedTab.getProperties().put(com.jlshell.ui.support.PluginOpenTiming.PROPERTY, timing);
                    openedTab.setOnClosed(() -> { timing.finish("closed-before-display"); deactivateAfterUserClose(item); });
                    workspaceTabs.add(openedTab);
                    workspaceTabs.select(openedTab);
                });
            }

            @Override
            public void openTab(String title, java.util.function.Supplier<javax.swing.JComponent> content) {
                openSwingTab(title, content);
            }

            @Override
            public void closeTab() {
                timing.finish("closed-before-display");
                SwingUtilities.invokeLater(() -> {
                    if (openedTab != null) workspaceTabs.remove(openedTab);
                    openedTab = null;
                });
            }

            @Override
            public void updateTabTitle(String title) {
                SwingUtilities.invokeLater(() -> {
                    if (openedTab != null) openedTab.setText(title);
                });
            }

            @Override
            public String resolveI18n(String key, String fallback) {
                return i18nService.getOrDefault(key, fallback);
            }
        };
    }

    private void deactivateAfterUserClose(LaunchItem item) {
        if (item.sessionPlugin() != null) {
            activatedSessionPluginIds.remove(item.ownerPluginId());
            pluginManager.deactivatePlugin(sessionId, item.ownerPluginId());
        } else if (programSessionRegistry != null) {
            programSessionRegistry.deactivate(sessionId, item.ownerPluginId());
        }
    }

    private void refreshOnUiThread() {
        SwingUtilities.invokeLater(() -> { if (!disposed) refreshPluginList(); });
    }

    private void refreshPluginList() {
        List<LaunchItem> available = new ArrayList<>();
        List<PluginDescriptor> sessionPlugins = pluginManager.getAvailablePlugins();
        sessionPlugins.forEach(plugin -> available.add(LaunchItem.session(plugin)));
        if (programSessionRegistry != null) {
            programSessionRegistry.contributions(sshContext)
                    .forEach(contribution -> available.add(LaunchItem.program(contribution)));
        }
        available.sort(java.util.Comparator.comparing(item ->
                item.displayName(i18nService.getLocale()), String.CASE_INSENSITIVE_ORDER));
        if (settings != null) {
            for (LaunchItem item : available) {
                if (Boolean.parseBoolean(settings.get("ui.pluginSidebar.pinned." + item.tabKey(), "false"))) pinnedIds.add(item.tabKey());
                else pinnedIds.remove(item.tabKey());
            }
        }
        availableItems = List.copyOf(available);
        publishPinnedShortcuts();
        Set<String> availableSessionIds = sessionPlugins.stream()
                .map(PluginDescriptor::id)
                .collect(java.util.stream.Collectors.toSet());
        activatedSessionPluginIds.retainAll(availableSessionIds);
    }

    private void publishPinnedShortcuts() {
        publishNativeList();
        pinnedShortcuts.set(availableItems.stream().filter(item -> pinnedIds.contains(item.tabKey()))
                .map(item -> new PinnedShortcut(item.tabKey(), item.displayName(i18nService.getLocale()), () -> requestOpen(item), item.icon()))
                .toList());
    }

    /** 列表组件在 EDT 延迟构建。 */
    public java.util.function.Supplier<javax.swing.JComponent> prepareNativeView() {
        var initial = nativeEntries();
        String search = i18nService.get("plugins.store.search"), open = i18nService.get("plugin.open"),
                pin = i18nService.get("plugin.pinToSidebar"), unpin = i18nService.get("plugin.unpinFromSidebar"), empty = i18nService.get("plugin.noPlugins");
        return () -> {
            var view = new com.jlshell.ui.swing.SwingPluginList(); nativeView.set(view);
            view.update(initial, search, open, pin, unpin, empty);
            return view;
        };
    }

    private List<com.jlshell.ui.swing.SwingPluginList.Entry> nativeEntries() {
        return availableItems.stream().map(item -> new com.jlshell.ui.swing.SwingPluginList.Entry(
                item.tabKey(), item.displayName(i18nService.getLocale()), item.description(i18nService.getLocale()),
                item.sessionPlugin() == null ? "Program" : item.sessionPlugin().version(), pinnedIds.contains(item.tabKey()),
                () -> requestOpen(item),
                selected -> com.jlshell.ui.support.UiThread.run(() -> {
                    if (disposed) return;
                    if (selected) pinnedIds.add(item.tabKey()); else pinnedIds.remove(item.tabKey());
                    if (settings != null) settings.set("ui.pluginSidebar.pinned." + item.tabKey(), Boolean.toString(selected));
                    publishPinnedShortcuts();
                }), item.icon())).toList();
    }

    private void publishNativeList() {
        var entries = nativeEntries();
        String search = i18nService.get("plugins.store.search"), open = i18nService.get("plugin.open"),
                pin = i18nService.get("plugin.pinToSidebar"), unpin = i18nService.get("plugin.unpinFromSidebar"), empty = i18nService.get("plugin.noPlugins");
        javax.swing.SwingUtilities.invokeLater(() -> {
            var view = nativeView.get(); if (view != null) view.update(entries, search, open, pin, unpin, empty);
        });
    }

    public void stopPlugins() {
        activationGeneration++;
        activatedSessionPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedSessionPluginIds.clear();
        if (programSessionRegistry != null) {
            programSessionRegistry.deactivateSession(sessionId);
        }
    }

    public void dispose() {
        disposed = true; nativeView.set(null);
        localeSubscription.close();
        catalogSubscription.close();
        programCatalogSubscription.close();
        stopPlugins();
        pinnedShortcuts.set(List.of());
    }

    private record LaunchItem(
            String ownerPluginId,
            PluginDescriptor sessionPlugin,
            ProgramSessionIntegrationRegistry.RegisteredContribution programContribution
    ) {
        static LaunchItem session(PluginDescriptor descriptor) {
            return new LaunchItem(descriptor.id(), descriptor, null);
        }

        static LaunchItem program(ProgramSessionIntegrationRegistry.RegisteredContribution contribution) {
            return new LaunchItem(contribution.pluginId(), null, contribution);
        }

        String tabKey() {
            return (sessionPlugin == null ? "program:" : "session:") + ownerPluginId;
        }

        com.jlshell.ui.swing.PluginIcons.Source icon() {
            Object plugin = sessionPlugin == null ? programContribution.contribution() : sessionPlugin.instance();
            return com.jlshell.ui.swing.PluginIcons.local(plugin == null ? null : plugin.getClass());
        }

        String displayName(java.util.Locale locale) {
            if (sessionPlugin != null) {
                JlShellPlugin plugin = sessionPlugin.instance();
                return plugin.displayName(locale);
            }
            ProgramSessionContribution contribution = programContribution.contribution();
            return contribution.displayName(locale);
        }

        String description(java.util.Locale locale) {
            if (sessionPlugin != null) {
                return sessionPlugin.instance().description(locale);
            }
            return programContribution.contribution().description(locale);
        }
    }
}
