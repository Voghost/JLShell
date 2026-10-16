package com.jlshell.program.plugin.loader;

import java.net.URL;
import java.net.URLClassLoader;
import com.jlshell.core.support.PerformanceTrace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.PluginCompatibility;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.api.security.PluginAccessDecision;
import com.jlshell.plugin.api.security.PluginAccessPolicyProvider;
import com.jlshell.plugin.api.security.PluginAccessRequest;
import com.jlshell.plugin.api.security.PluginOperation;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.SecureStorage;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.PluginCapabilityRegistryView;
import com.jlshell.plugin.loader.PluginEnablementService;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.loader.PluginRuntimeServices;
import com.jlshell.plugin.loader.RuntimePluginDirectories;
import com.jlshell.plugin.loader.store.PluginPackageValidator;
import com.jlshell.program.api.ProgramApiContext;
import com.jlshell.program.api.ProgramApiProvider;
import com.jlshell.program.api.AccountSessionService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProgramPluginManager {

    private static final Logger log = LoggerFactory.getLogger(ProgramPluginManager.class);
    private static final String DEFAULT_HOST_VERSION = "0.1.0";

    private final String userPluginsDir;
    private final String hostVersion;
    private final CapabilityRegistryImpl globalRegistry;
    private final CapabilityBus capabilityBus;
    private final PluginManager pluginManager;
    private final PluginEnablementService enablementService;
    private final Function<String, PluginStorage> storageFactory;
    private final Function<String, SecureStorage> secureStorageFactory;
    private final ProjectIntegrationRegistry projectIntegrationRegistry;
    private final ProgramSessionIntegrationRegistry sessionIntegrationRegistry;
    private final ProgramConnectionIntegrationRegistry connectionIntegrationRegistry;
    private final DefaultProgramPluginContext.Callbacks callbacks;
    private final ProgramApiContext programApiContext;
    private final AccountSessionService accountSessionService;
    private final List<ProgramPluginDescriptor> plugins = new ArrayList<>();
    private final Map<String, JlShellProgramPlugin> active = new ConcurrentHashMap<>();
    private final java.util.Set<JlShellProgramPlugin> trustedPolicyPlugins =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean loaded;

    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks) {
        this(userPluginsDir, hostVersion, pluginManager, globalRegistry, capabilityBus, storageFactory, callbacks, null);
    }

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks,
                                ProgramApiContext programApiContext) {
        this(userPluginsDir, hostVersion, pluginManager, globalRegistry, capabilityBus, storageFactory,
                callbacks, programApiContext, new PluginEnablementService());
    }

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks,
                                ProgramApiContext programApiContext,
                                PluginEnablementService enablementService) {
        this(userPluginsDir, hostVersion, pluginManager, globalRegistry, capabilityBus,
                storageFactory, null, callbacks, programApiContext, enablementService,
                ProjectIntegrationRegistry.shared());
    }

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                Function<String, SecureStorage> secureStorageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks,
                                ProjectIntegrationRegistry projectIntegrationRegistry) {
        this(userPluginsDir, hostVersion, pluginManager, globalRegistry, capabilityBus,
                storageFactory, secureStorageFactory, callbacks, null,
                new PluginEnablementService(), projectIntegrationRegistry, AccountSessionService.unavailable());
    }

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                Function<String, SecureStorage> secureStorageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks,
                                ProgramApiContext programApiContext,
                                PluginEnablementService enablementService,
                                ProjectIntegrationRegistry projectIntegrationRegistry) {
        this(userPluginsDir, hostVersion, pluginManager, globalRegistry, capabilityBus, storageFactory,
                secureStorageFactory, callbacks, programApiContext, enablementService,
                projectIntegrationRegistry, AccountSessionService.unavailable());
    }

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                Function<String, SecureStorage> secureStorageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks,
                                ProgramApiContext programApiContext,
                                PluginEnablementService enablementService,
                                ProjectIntegrationRegistry projectIntegrationRegistry,
                                AccountSessionService accountSessionService) {
        this.userPluginsDir = userPluginsDir;
        this.hostVersion = hostVersion == null || hostVersion.isBlank() ? DEFAULT_HOST_VERSION : hostVersion;
        this.pluginManager = pluginManager;
        this.enablementService = enablementService == null ? new PluginEnablementService() : enablementService;
        this.globalRegistry = globalRegistry;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.secureStorageFactory = secureStorageFactory;
        this.callbacks = callbacks;
        this.programApiContext = programApiContext;
        this.accountSessionService = accountSessionService == null
                ? AccountSessionService.unavailable() : accountSessionService;
        this.projectIntegrationRegistry = projectIntegrationRegistry == null
                ? ProjectIntegrationRegistry.shared() : projectIntegrationRegistry;
        this.sessionIntegrationRegistry = new ProgramSessionIntegrationRegistry(pluginManager);
        this.connectionIntegrationRegistry = new ProgramConnectionIntegrationRegistry();
    }

    public void ensureLoaded() { ensureLoaded(null); }

    public void ensureLoaded(PerformanceTrace parent) {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            loadPlugins(parent);
            activateAll(parent);
            loaded = true;
        }
    }

    public void loadPlugins() { loadPlugins(null); }

    private void loadPlugins(PerformanceTrace parent) {
        var timing = parent == null ? PerformanceTrace.start("plugin-scan", "program")
                : parent.child("plugin-scan", "program");
        try {
            plugins.clear();
            trustedPolicyPlugins.clear();
            loadFromClassLoader(Thread.currentThread().getContextClassLoader(), true, timing);
            loadFromExternalDirs(timing);
            log.info("Loaded {} program plugin(s)", plugins.size());
            timing.finish("success");
        } catch (RuntimeException | Error error) { timing.failed(error); throw error; }
    }

    /** 商店安装 PROGRAM 插件后供下次启动前的管理页刷新使用。 */
    public synchronized void reloadPlugins() {
        deactivateAll();
        loaded = false;
        ensureLoaded();
    }

    private void loadFromClassLoader(ClassLoader classLoader, boolean trustedSource, PerformanceTrace timing) {
        var providers = timing.call("service-discovery", () ->
                ServiceLoader.load(JlShellProgramPlugin.class, classLoader).stream().toList());
        for (var provider : providers) {
            var providerTiming = timing.child("program-plugin-provider", provider.type().getName());
            try {
                var plugin = providerTiming.call("constructor", provider::get);
                plugins.add(providerTiming.call("metadata-and-context", () -> toDescriptor(plugin)));
                if (trustedSource && plugin.accessPolicyProvider() != null) trustedPolicyPlugins.add(plugin);
                providerTiming.mark("plugin-id-" + plugin.id());
                providerTiming.finish("success");
            } catch (RuntimeException | Error error) { providerTiming.failed(error); throw error; }
        }
    }

    private void loadFromExternalDirs(PerformanceTrace timing) {
        var directories = timing.call("resolve-directories", () -> RuntimePluginDirectories.resolve(userPluginsDir, "program-plugins"));
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) continue;
            var jars = timing.call("list-jars", () -> RuntimePluginDirectories.pluginJars(dir));
            for (Path jarPath : jars) {
                var jarTiming = timing.child("program-plugin-jar", jarPath.getFileName().toString());
                try {
                    jarTiming.call("validate-package", () -> { PluginPackageValidator.validateForLoading(jarPath, PluginScope.PROGRAM); return null; });
                    URL[] urls = {jarPath.toUri().toURL()};
                    URLClassLoader loader = jarTiming.call("classloader", () ->
                            new URLClassLoader(urls, Thread.currentThread().getContextClassLoader()));
                    loadFromClassLoader(loader, false, jarTiming);
                    jarTiming.finish("success");
                } catch (Exception error) {
                    jarTiming.failed(error);
                    log.warn("Failed to load program plugin JAR: {}", jarPath.getFileName(), error);
                } catch (Error error) { jarTiming.failed(error); throw error; }
            }
        }
    }

    public List<ProgramPluginDescriptor> getAvailablePlugins() {
        ensureLoaded();
        return plugins.stream().filter(descriptor -> isPluginEnabled(descriptor.id())).toList();
    }

    /** 包含已停用插件，供插件管理页展示。 */
    public List<ProgramPluginDescriptor> getInstalledPlugins() {
        ensureLoaded();
        return List.copyOf(plugins);
    }

    public boolean isPluginEnabled(String pluginId) {
        return enablementService.isEnabled(pluginId, PluginScope.PROGRAM);
    }

    public synchronized void setPluginEnabled(String pluginId, boolean enabled) {
        if (enabled == isPluginEnabled(pluginId)) return;
        if (enabled) {
            enablementService.setEnabled(pluginId, PluginScope.PROGRAM, true);
            plugins.stream().filter(descriptor -> descriptor.id().equals(pluginId))
                    .findFirst().ifPresent(descriptor -> activateDescriptor(
                            descriptor, trustedPolicyPlugins.contains(descriptor.instance())));
        } else {
            deactivatePlugin(pluginId);
            enablementService.setEnabled(pluginId, PluginScope.PROGRAM, false);
        }
    }

    public void activateAll() { activateAll(null); }

    private void activateAll(PerformanceTrace parent) {
        plugins.stream()
                .filter(descriptor -> isPluginEnabled(descriptor.id()))
                .filter(descriptor -> trustedPolicyPlugins.contains(descriptor.instance()))
                .forEach(descriptor -> activateDescriptor(descriptor, true, parent));
        plugins.stream()
                .filter(descriptor -> isPluginEnabled(descriptor.id()))
                .filter(descriptor -> !trustedPolicyPlugins.contains(descriptor.instance()))
                .forEach(descriptor -> activateDescriptor(descriptor, false, parent));
    }

    private void activateDescriptor(ProgramPluginDescriptor descriptor, boolean trustedPolicyProvider) {
        activateDescriptor(descriptor, trustedPolicyProvider, null);
    }

    private void activateDescriptor(ProgramPluginDescriptor descriptor, boolean trustedPolicyProvider, PerformanceTrace parent) {
        if (active.containsKey(descriptor.id())) return;
        boolean pluginActivated = false;
        var timing = parent == null ? PerformanceTrace.start("program-plugin-activate", descriptor.id())
                : parent.child("program-plugin-activate", descriptor.id());
        try {
            if (!trustedPolicyProvider) {
                PluginAccessDecision decision = timing.call("access-policy", () -> pluginManager.accessController().evaluate(new PluginAccessRequest(
                        PluginOperation.ACTIVATE, PluginScope.PROGRAM, null, descriptor.id(), null)));
                if (decision.effect() == PluginAccessDecision.Effect.DENY) {
                    log.info("Activation denied for program plugin {}: {}", descriptor.id(), decision.reason());
                    disposeDescriptorContext(descriptor);
                    timing.finish("denied");
                    return;
                }
            }
            if (descriptor.context() instanceof DefaultProgramPluginContext context && !context.fonts().available()) {
                context.attachFonts(pluginManager.fontsForContext());
            }
            if (descriptor.context() instanceof PluginContext pluginContext) {
                pluginManager.adoptContext(null, descriptor.id(), pluginContext);
            }
            timing.run("plugin-code", () -> descriptor.instance().activate(descriptor.context()));
            pluginActivated = true;
            if (programApiContext != null && descriptor.instance() instanceof ProgramApiProvider provider) {
                timing.run("program-api-provider", () -> provider.activate(programApiContext));
            }
            if (trustedPolicyProvider) {
                pluginManager.accessController().registerTrusted(
                        descriptor.id(), descriptor.instance().accessPolicyProvider());
            } else if (descriptor.instance().accessPolicyProvider() != null) {
                log.warn("Ignored access policy provider from untrusted plugin {}", descriptor.id());
            }
            active.put(descriptor.id(), descriptor.instance());
            timing.finish("success");
        } catch (Exception e) {
            timing.failed(e);
            active.remove(descriptor.id(), descriptor.instance());
            pluginManager.accessController().unregister(descriptor.id());
            projectIntegrationRegistry.clearForPlugin(descriptor.id());
            connectionIntegrationRegistry.clearForPlugin(descriptor.id());
            globalRegistry.clearForPlugin(descriptor.id());
            if (pluginActivated) {
                try {
                    descriptor.instance().deactivate();
                } catch (RuntimeException cleanupError) {
                    log.warn("Failed to roll back program plugin {}", descriptor.id(), cleanupError);
                }
            }
            disposeDescriptorContext(descriptor);
            log.warn("Failed to activate program plugin {}", descriptor.id(), e);
        } catch (Error error) { timing.failed(error); throw error; }
    }

    private void deactivatePlugin(String pluginId) {
        JlShellProgramPlugin plugin = active.remove(pluginId);
        if (plugin == null) return;
        try {
            if (plugin instanceof ProgramApiProvider provider) provider.deactivate();
            plugin.deactivate();
        } finally {
            pluginManager.accessController().unregister(pluginId);
            projectIntegrationRegistry.clearForPlugin(pluginId);
            connectionIntegrationRegistry.clearForPlugin(pluginId);
            globalRegistry.clearForPlugin(pluginId);
            plugins.stream()
                    .filter(descriptor -> descriptor.id().equals(pluginId))
                    .findFirst()
                    .ifPresent(this::disposeDescriptorContext);
        }
    }

    private void disposeDescriptorContext(ProgramPluginDescriptor descriptor) {
        if (descriptor.context() instanceof DefaultProgramPluginContext context) {
            context.dispose();
            pluginManager.releaseContext(null, descriptor.id(), context);
        }
    }

    public void deactivateAll() {
        List.copyOf(active.keySet()).forEach(this::deactivatePlugin);
    }

    public StringProperty themeNameProperty() {
        return themeName;
    }

    public ObjectProperty<Locale> localeProperty() {
        return locale;
    }

    public ProgramSessionIntegrationRegistry sessionIntegrationRegistry() {
        return sessionIntegrationRegistry;
    }

    /** Program 插件注册的连接前本地回环路由。 */
    public ProgramConnectionIntegrationRegistry connectionIntegrationRegistry() {
        return connectionIntegrationRegistry;
    }

    public void setThemeName(String name) {
        themeName.set(name);
        plugins.forEach(d -> {
            if (d.context() instanceof DefaultProgramPluginContext ctx) {
                ctx.setThemeName(name);
            }
        });
    }

    public void setLocale(Locale loc) {
        locale.set(loc);
        plugins.forEach(d -> {
            if (d.context() instanceof DefaultProgramPluginContext ctx) {
                ctx.setLocale(loc);
            }
        });
    }

    private ProgramPluginDescriptor toDescriptor(JlShellProgramPlugin plugin) {
        PluginCompatibility.Result compatibility = PluginCompatibility.evaluate(hostVersion,
                plugin.minHostVersionInclusive(),
                plugin.maxHostVersionInclusive());
        DefaultProgramPluginContext ctx = new DefaultProgramPluginContext(
                plugin.id(),
                new PluginCapabilityRegistryView(globalRegistry, plugin.id()),
                capabilityBus,
                storageFactory == null ? null : storageFactory.apply(plugin.id()),
                secureStorageFactory == null ? SecureStorage.unavailable() : secureStorageFactory.apply(plugin.id()),
                projectIntegrationRegistry.scoped(plugin.id()),
                PluginRuntimeServices.hostEvents("program/" + plugin.id()),
                sessionIntegrationRegistry.scoped(plugin.id()),
                connectionIntegrationRegistry.scoped(plugin.id()),
                pluginManager.accessController(),
                accountSessionService,
                callbacks
        );
        ctx.setThemeName(themeName.get());
        ctx.attachFonts(pluginManager.fontsForContext());
        ctx.setLocale(locale.get());
        return new ProgramPluginDescriptor(
                plugin.id(),
                plugin.displayName(),
                plugin.version(),
                plugin.author(),
                plugin.description(),
                plugin.minHostVersionInclusive(),
                plugin.maxHostVersionInclusive(),
                compatibility.status(),
                compatibility.warning(),
                plugin,
                ctx
        );
    }
}
