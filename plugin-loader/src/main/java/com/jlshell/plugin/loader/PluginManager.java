package com.jlshell.plugin.loader;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and manages plugin lifecycle.
 * Loads plugins from the classpath via ServiceLoader and from an external directory.
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final String pluginsDir;
    private final List<PluginDescriptor> plugins = new ArrayList<>();
    private final Map<String, JlShellPlugin> activePlugins = new ConcurrentHashMap<>();

    public PluginManager(String pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    public PluginManager() {
        this(System.getProperty("user.home") + "/.jlshell/plugins");
    }

    public void loadPlugins() {
        plugins.clear();
        loadFromClassLoader(Thread.currentThread().getContextClassLoader());
        loadFromExternalDir();
        log.info("Loaded {} plugin(s)", plugins.size());
    }

    private void loadFromClassLoader(ClassLoader classLoader) {
        ServiceLoader.load(JlShellPlugin.class, classLoader).forEach(plugin -> {
            plugins.add(toDescriptor(plugin));
            log.debug("Discovered plugin: {} ({})", plugin.displayName(), plugin.id());
        });
    }

    private void loadFromExternalDir() {
        File dir = new File(pluginsDir);
        if (!dir.isDirectory()) {
            return;
        }
        File[] jars = dir.listFiles(f -> f.getName().endsWith(".jar"));
        if (jars == null) {
            return;
        }
        for (File jar : jars) {
            try {
                URL[] urls = {jar.toURI().toURL()};
                URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
                loadFromClassLoader(loader);
                log.info("Loaded plugins from external JAR: {}", jar.getName());
            } catch (Exception e) {
                log.warn("Failed to load plugin JAR : {}", jar.getName(), e.getMessage());
            }
        }
    }

    public List<PluginDescriptor> getAvailablePlugins() {
        return List.copyOf(plugins);
    }

    public void activatePlugin(String pluginId, PluginContext context) {
        plugins.stream()
                .filter(d -> d.id().equals(pluginId))
                .findFirst()
                .ifPresent(descriptor -> {
                    JlShellPlugin plugin = descriptor.instance();
                    plugin.activate(context);
                    activePlugins.put(pluginId, plugin);
                    log.debug("Activated plugin: {}", pluginId);
                });
    }

    public void deactivatePlugin(String pluginId) {
        JlShellPlugin plugin = activePlugins.remove(pluginId);
        if (plugin != null) {
            plugin.deactivate();
            log.debug("Deactivated plugin: {}", pluginId);
        }
    }

    public void deactivateAll() {
        new ArrayList<>(activePlugins.keySet()).forEach(this::deactivatePlugin);
    }

    private static PluginDescriptor toDescriptor(JlShellPlugin plugin) {
        return new PluginDescriptor(
                plugin.id(),
                plugin.displayName(),
                plugin.version(),
                plugin.description(),
                plugin.requiresSshSession(),
                plugin
        );
    }
}
