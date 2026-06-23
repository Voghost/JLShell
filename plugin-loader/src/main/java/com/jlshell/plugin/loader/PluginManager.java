package com.jlshell.plugin.loader;

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

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and manages plugin lifecycle.
 * Loads plugins from the classpath via ServiceLoader and from external directories.
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private static final String GLOBAL_KEY = "__global__";

    // 未知 sessionId 的共享只读空哨兵 registry：resolve/specs 返回空，禁止向其 register。
    // 任何 register 到此对象的调用都是调用方 bug（host 只经真实 session 桶的 DefaultPluginContext 注册）。
    private static final CapabilityRegistryImpl EMPTY_TRANSIENT = new CapabilityRegistryImpl();

    private final String userPluginsDir;
    private final List<PluginDescriptor> plugins = new ArrayList<>();
    private final Map<String, SessionPluginSet> activeBySession = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());

    public PluginManager(String userPluginsDir) {
        this.userPluginsDir = userPluginsDir;
    }

    public PluginManager() {
        this(System.getProperty("user.home") + "/.jlshell/plugins");
    }

    /**
     * 显式触发加载。通常由 {@link #ensureLoaded()} 在首次访问时自动调用，
     * 保留 public 仅供测试或特殊场景使用。
     */
    public void loadPlugins() {
        plugins.clear();
        loadFromClassLoader(Thread.currentThread().getContextClassLoader());
        loadFromExternalDirs();
        log.info("Loaded {} plugin(s)", plugins.size());
    }

    /**
     * 延迟加载入口：首次访问插件列表/激活插件时才扫描 JAR。
     * 把 4.7MB sysmon JAR 的加载从启动路径移到首次开终端/插件 Tab。
     */
    public void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            loadPlugins();
            loaded = true;
        }
    }

    private void loadFromClassLoader(ClassLoader classLoader) {
        ServiceLoader.load(JlShellPlugin.class, classLoader).forEach(plugin -> {
            plugins.add(toDescriptor(plugin));
            log.debug("Discovered plugin: {} ({})", plugin.displayName(), plugin.id());
        });
    }

    private void loadFromExternalDirs() {
        loadFromDirectory(Path.of(userPluginsDir));
        Path appDir = resolveApplicationDir();
        if (appDir != null) {
            Path bundledDir = appDir.resolve("plugins");
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
                log.info("Loaded plugins from: {}", jar.getName());
            } catch (Exception e) {
                log.warn("Failed to load plugin JAR: {}", jar.getName(), e.getMessage());
            }
        }
    }

    private static Path resolveApplicationDir() {
        // 1. Explicit system property override
        String appDir = System.getProperty("jlshell.app.dir");
        if (appDir != null && !appDir.isBlank()) return Path.of(appDir);

        // 2. Code-source location (works for jpackage app-image, jar-in-exe, etc.)
        try {
            Path codeSourcePath = Path.of(PluginManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSourcePath)) return codeSourcePath.getParent();
            if (Files.isDirectory(codeSourcePath)) return codeSourcePath;
        } catch (Exception ignored) {}

        // 3. Fallback: process executable path (Launch4j exe on Windows)
        try {
            String cmd = java.lang.ProcessHandle.current().info().command().orElse("");
            if (!cmd.isEmpty()) {
                Path exePath = Path.of(cmd);
                if (Files.isRegularFile(exePath)) return exePath.getParent();
            }
        } catch (Exception ignored) {}

        return null;
    }

    public List<PluginDescriptor> getAvailablePlugins() {
        ensureLoaded();
        return List.copyOf(plugins);
    }

    public StringProperty themeNameProperty() {
        return themeName;
    }

    public ObjectProperty<Locale> localeProperty() {
        return locale;
    }

    public void setThemeName(String name) {
        themeName.set(name);
        notifyThemeChanged(name);
    }

    public void setLocale(Locale loc) {
        locale.set(loc);
        notifyLocaleChanged(loc);
    }

    public void activatePlugin(String pluginId, PluginContext context) {
        ensureLoaded();
        plugins.stream()
                .filter(d -> d.id().equals(pluginId))
                .findFirst()
                .ifPresent(descriptor -> activateInstance(descriptor.instance(), context));
    }

    /**
     * 经 per-session 路径激活一个已构造的插件实例。供测试 + host 已持有 descriptor 时使用。
     * 若 context 是 DefaultPluginContext，按其 sessionId 路由；否则落到 __global__ 桶。
     */
    public void activateInstance(JlShellPlugin plugin, PluginContext context) {
        ensureLoaded();
        String sid = (context instanceof DefaultPluginContext dpc) ? dpc.sessionId() : null;
        SessionPluginSet set = activeBySession.computeIfAbsent(
                sid == null ? GLOBAL_KEY : sid, SessionPluginSet::new);
        set.plugins.put(plugin.id(), plugin);
        set.contexts.put(plugin.id(), context);
        plugin.activate(context);
        log.debug("Activated plugin {} in session {}", plugin.id(), sid);
    }

    /** 供 CapabilityBus 用：按 sessionId 取该会话的 registry。sessionId 为 null 取全局桶。 */
    public CapabilityRegistryImpl registryFor(String sessionId) {
        if (sessionId == null) {
            return activeBySession.computeIfAbsent(GLOBAL_KEY, SessionPluginSet::new).registry;
        }
        SessionPluginSet set = activeBySession.get(sessionId);
        return set != null ? set.registry : EMPTY_TRANSIENT;
    }

    public CapabilityRegistryImpl globalRegistry() { return registryFor(null); }

    /** 供 CapabilityBus 构造 CapabilityContext 时取插件的 PluginContext。 */
    public PluginContext contextFor(String sessionId, String pluginId) {
        SessionPluginSet set = activeBySession.get((sessionId == null) ? GLOBAL_KEY : sessionId);
        return set == null ? null : set.contexts.get(pluginId);
    }

    /** 供 host 在自建 context 后挂到某 session 桶（激活由 host 完成）。 */
    public void adoptContext(String sessionId, String pluginId, PluginContext ctx) {
        SessionPluginSet set = activeBySession.computeIfAbsent(
                (sessionId == null) ? GLOBAL_KEY : sessionId, SessionPluginSet::new);
        set.contexts.put(pluginId, ctx);
    }

    /** 按会话停用单个插件。 */
    public void deactivatePlugin(String sessionId, String pluginId) {
        String key = (sessionId == null) ? GLOBAL_KEY : sessionId;
        SessionPluginSet set = activeBySession.get(key);
        if (set == null) return;
        JlShellPlugin plugin = set.plugins.remove(pluginId);
        set.contexts.remove(pluginId);
        if (plugin != null) {
            set.registry.clearForPlugin(pluginId);
            PluginView view = plugin.view();
            if (view != null) view.onSessionClosed();
            plugin.deactivate();
            log.debug("Deactivated plugin {} in session {}", pluginId, sessionId);
        }
        // 桶空了就回收；2-arg remove 仅在值仍为 set 时移除，避免误删他线程重建的桶。
        if (set.plugins.isEmpty()) {
            activeBySession.remove(key, set);
        }
    }

    /** 旧接口：跨所有会话停用某个插件。签名保持不变以兼容现有 host 调用点。 */
    public void deactivatePlugin(String pluginId) {
        activeBySession.forEach((key, set) -> {
            JlShellPlugin plugin = set.plugins.remove(pluginId);
            set.contexts.remove(pluginId);
            if (plugin != null) {
                set.registry.clearForPlugin(pluginId);
                PluginView view = plugin.view();
                if (view != null) view.onSessionClosed();
                plugin.deactivate();
            }
            // 桶空了就回收；2-arg remove 仅在值仍为 set 时移除，避免误删他线程重建的桶。
            if (set.plugins.isEmpty()) {
                activeBySession.remove(key, set);
            }
        });
    }

    public void deactivateAll() {
        activeBySession.values().forEach(set -> {
            set.plugins.values().forEach(p -> {
                PluginView view = p.view();
                if (view != null) view.onSessionClosed();
                p.deactivate();
            });
            set.plugins.clear();
            set.contexts.clear();
            set.registry.clear();
        });
        activeBySession.clear();
    }

    private void notifyThemeChanged(String themeName) {
        activeBySession.values().stream()
                .flatMap(s -> s.plugins.values().stream())
                .forEach(p -> {
                    PluginView view = p.view();
                    if (view != null) view.onThemeChanged(themeName);
                });
    }

    private void notifyLocaleChanged(Locale locale) {
        activeBySession.values().stream()
                .flatMap(s -> s.plugins.values().stream())
                .forEach(p -> {
                    PluginView view = p.view();
                    if (view != null) view.onLocaleChanged(locale);
                });
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
