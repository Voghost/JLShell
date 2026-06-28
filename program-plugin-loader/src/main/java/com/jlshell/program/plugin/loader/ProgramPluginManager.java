package com.jlshell.program.plugin.loader;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.PluginCompatibility;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.PluginCapabilityRegistryView;
import com.jlshell.plugin.loader.PluginManager;
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
    private final Function<String, PluginStorage> storageFactory;
    private final DefaultProgramPluginContext.Callbacks callbacks;
    private final List<ProgramPluginDescriptor> plugins = new ArrayList<>();
    private final Map<String, JlShellProgramPlugin> active = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());

    public ProgramPluginManager(String userPluginsDir, String hostVersion,
                                PluginManager pluginManager,
                                CapabilityRegistryImpl globalRegistry,
                                CapabilityBus capabilityBus,
                                Function<String, PluginStorage> storageFactory,
                                DefaultProgramPluginContext.Callbacks callbacks) {
        this.userPluginsDir = userPluginsDir;
        this.hostVersion = hostVersion == null || hostVersion.isBlank() ? DEFAULT_HOST_VERSION : hostVersion;
        this.pluginManager = pluginManager;
        this.globalRegistry = globalRegistry;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.callbacks = callbacks;
    }

    public void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            loadPlugins();
            activateAll();
            loaded = true;
        }
    }

    public void loadPlugins() {
        plugins.clear();
        loadFromClassLoader(Thread.currentThread().getContextClassLoader());
        loadFromExternalDirs();
        log.info("Loaded {} program plugin(s)", plugins.size());
    }

    private void loadFromClassLoader(ClassLoader classLoader) {
        ServiceLoader.load(JlShellProgramPlugin.class, classLoader).forEach(plugin -> {
            plugins.add(toDescriptor(plugin));
            log.debug("Discovered program plugin: {} ({})", plugin.displayName(), plugin.id());
        });
    }

    private void loadFromExternalDirs() {
        loadFromDirectory(Path.of(userPluginsDir));
        Path appDir = resolveApplicationDir();
        if (appDir != null) {
            Path bundledDir = appDir.resolve("program-plugins");
            if (!bundledDir.equals(Path.of(userPluginsDir))) {
                loadFromDirectory(bundledDir);
            }
        }
    }

    private void loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        File[] jars = dir.toFile().listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            try {
                URL[] urls = {jar.toURI().toURL()};
                URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
                loadFromClassLoader(loader);
                log.info("Loaded program plugins from: {}", jar.getName());
            } catch (Exception e) {
                log.warn("Failed to load program plugin JAR: {}", jar.getName(), e);
            }
        }
    }

    private static Path resolveApplicationDir() {
        String appDir = System.getProperty("jlshell.app.dir");
        if (appDir != null && !appDir.isBlank()) return Path.of(appDir);
        try {
            Path codeSourcePath = Path.of(ProgramPluginManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSourcePath)) return codeSourcePath.getParent();
            if (Files.isDirectory(codeSourcePath)) return codeSourcePath;
        } catch (Exception ignored) {}
        try {
            String cmd = java.lang.ProcessHandle.current().info().command().orElse("");
            if (!cmd.isEmpty()) {
                Path exePath = Path.of(cmd);
                if (Files.isRegularFile(exePath)) return exePath.getParent();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public List<ProgramPluginDescriptor> getAvailablePlugins() {
        ensureLoaded();
        return List.copyOf(plugins);
    }

    public void activateAll() {
        for (ProgramPluginDescriptor descriptor : plugins) {
            if (active.containsKey(descriptor.id())) {
                continue;
            }
            try {
                descriptor.instance().activate(descriptor.context());
                active.put(descriptor.id(), descriptor.instance());
            } catch (Exception e) {
                log.warn("Failed to activate program plugin {}", descriptor.id(), e);
            }
        }
    }

    public void deactivateAll() {
        active.forEach((id, plugin) -> {
            try {
                plugin.deactivate();
            } finally {
                globalRegistry.clearForPlugin(id);
            }
        });
        active.clear();
    }

    public StringProperty themeNameProperty() {
        return themeName;
    }

    public ObjectProperty<Locale> localeProperty() {
        return locale;
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
                callbacks
        );
        ctx.setThemeName(themeName.get());
        ctx.setLocale(locale.get());
        if (pluginManager != null) {
            pluginManager.adoptContext(null, plugin.id(), ctx);
        }
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
