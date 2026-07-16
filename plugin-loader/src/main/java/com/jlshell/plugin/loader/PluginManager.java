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
import com.jlshell.plugin.api.PluginCompatibility;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.loader.store.PluginPackageValidator;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
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
    private static final String DEFAULT_HOST_VERSION = "0.1.0";

    // 未知 sessionId 的共享只读空哨兵 registry：resolve/specs 返回空，禁止向其 register。
    // 任何 register 到此对象的调用都是调用方 bug（host 只经真实 session 桶的 DefaultPluginContext 注册）。
    private static final CapabilityRegistryImpl EMPTY_TRANSIENT = new CapabilityRegistryImpl();

    private final String userPluginsDir;
    private final String hostVersion;
    private final PluginEnablementService enablementService;
    private final List<PluginDescriptor> plugins = new ArrayList<>();
    private final List<URLClassLoader> externalClassLoaders = new ArrayList<>();
    private final Map<String, SessionPluginSet> activeBySession = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());
    /** UI 通过该版本号感知插件目录重新扫描，避免每个 Session 持有过期列表。 */
    private final ReadOnlyLongWrapper catalogRevision = new ReadOnlyLongWrapper(0);

    public PluginManager(String userPluginsDir) {
        this(userPluginsDir, DEFAULT_HOST_VERSION, new PluginEnablementService());
    }

    public PluginManager(String userPluginsDir, String hostVersion) {
        this(userPluginsDir, hostVersion, new PluginEnablementService());
    }

    public PluginManager(String userPluginsDir, String hostVersion,
                         PluginEnablementService enablementService) {
        this.userPluginsDir = userPluginsDir;
        this.hostVersion = hostVersion == null || hostVersion.isBlank() ? DEFAULT_HOST_VERSION : hostVersion;
        this.enablementService = enablementService == null ? new PluginEnablementService() : enablementService;
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
        closeExternalClassLoaders();
        loadFromClassLoader(Thread.currentThread().getContextClassLoader());
        loadFromExternalDirs();
        catalogRevision.set(catalogRevision.get() + 1);
        log.info("Loaded {} plugin(s)", plugins.size());
    }

    /**
     * 停用全部会话插件后重新扫描外部目录。
     * 商店安装或升级 SESSION 插件后调用，使新 JAR 在无需重启的情况下生效。
     */
    public synchronized void reloadPlugins() {
        deactivateAll();
        loaded = false;
        ensureLoaded();
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
        for (Path dir : RuntimePluginDirectories.resolve(userPluginsDir, "plugins")) {
            loadFromDirectory(dir);
        }
    }

    private void loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        for (Path jarPath : RuntimePluginDirectories.pluginJars(dir)) {
            File jar = jarPath.toFile();
            try {
                PluginPackageValidator.validateForLoading(jarPath, PluginScope.SESSION);
                URL[] urls = {jar.toURI().toURL()};
                URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
                externalClassLoaders.add(loader);
                loadFromClassLoader(loader);
                log.info("Loaded plugins from: {}", jar.getName());
            } catch (Exception e) {
                log.warn("Failed to load plugin JAR: {}", jar.getName(), e.getMessage());
            }
        }
    }

    private void closeExternalClassLoaders() {
        externalClassLoaders.forEach(loader -> {
            try {
                loader.close();
            } catch (java.io.IOException e) {
                log.debug("Failed to close external plugin class loader", e);
            }
        });
        externalClassLoaders.clear();
    }

    public List<PluginDescriptor> getAvailablePlugins() {
        ensureLoaded();
        return plugins.stream().filter(descriptor -> isPluginEnabled(descriptor.id())).toList();
    }

    /** 包含已停用插件，供“已安装”管理页展示。 */
    public List<PluginDescriptor> getInstalledPlugins() {
        ensureLoaded();
        return List.copyOf(plugins);
    }

    public boolean isPluginEnabled(String pluginId) {
        return enablementService.isEnabled(pluginId, PluginScope.SESSION);
    }

    /** 停用会立即关闭所有 Session 中的实例；启用后会重新出现在 Session 插件列表。 */
    public void setPluginEnabled(String pluginId, boolean enabled) {
        if (!enabled) deactivatePlugin(pluginId);
        enablementService.setEnabled(pluginId, PluginScope.SESSION, enabled);
        catalogRevision.set(catalogRevision.get() + 1);
    }

    /** 每次重新扫描插件目录后递增，供所有已打开的 Session 页面刷新插件列表。 */
    public ReadOnlyLongProperty catalogRevisionProperty() {
        return catalogRevision.getReadOnlyProperty();
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
        if (!isPluginEnabled(pluginId)) return;
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
        if (!isPluginEnabled(plugin.id())) return;
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

    /**
     * 确保某 session 的桶存在并返回其 registry（host 构建 DefaultPluginContext 前调用）。
     * 与 {@link #registryFor(String)} 的区别：后者对未知 sessionId 返回只读空哨兵，
     * 仅供 CapabilityBus 做只读查找；本方法始终创建并返回真实桶的 registry，
     * 供 host 在新建插件上下文时拿到与桶一致、可写入的 registry。
     */
    public CapabilityRegistryImpl registryForSession(String sessionId) {
        return activeBySession.computeIfAbsent(
                (sessionId == null) ? GLOBAL_KEY : sessionId, SessionPluginSet::new).registry;
    }

    public CapabilityRegistryImpl globalRegistry() { return registryFor(null); }

    /** 判断指定插件是否已在某个 session（null 为全局）激活。 */
    public boolean isPluginActive(String sessionId, String pluginId) {
        SessionPluginSet set = activeBySession.get((sessionId == null) ? GLOBAL_KEY : sessionId);
        return set != null && set.plugins.containsKey(pluginId);
    }

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
        PluginContext context = set.contexts.remove(pluginId);
        if (plugin != null) {
            set.registry.clearForPlugin(pluginId);
            disposeContext(context);
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
            PluginContext context = set.contexts.remove(pluginId);
            if (plugin != null) {
                set.registry.clearForPlugin(pluginId);
                disposeContext(context);
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

    /** 停用并清理一个 session 中的所有插件（包括 API 静默激活的插件）。 */
    public void deactivateSession(String sessionId) {
        String key = (sessionId == null) ? GLOBAL_KEY : sessionId;
        SessionPluginSet set = activeBySession.remove(key);
        if (set == null) return;
        set.plugins.values().forEach(plugin -> {
            disposeContext(set.contexts.get(plugin.id()));
            PluginView view = plugin.view();
            if (view != null) view.onSessionClosed();
            plugin.deactivate();
        });
        set.plugins.clear();
        set.contexts.clear();
        set.registry.clear();
        log.debug("Deactivated all plugins in session {}", sessionId);
    }

    public void deactivateAll() {
        activeBySession.values().forEach(set -> {
            set.plugins.values().forEach(p -> {
                disposeContext(set.contexts.get(p.id()));
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

    private static void disposeContext(PluginContext context) {
        if (context instanceof DefaultPluginContext defaultContext) {
            // 插件被卸载、升级或会话关闭时，不保留已经失效的插件页面。
            try {
                defaultContext.closeTab();
            } catch (RuntimeException e) {
                log.warn("Failed to close plugin tab while disposing context", e);
            } finally {
                defaultContext.disposeBindings();
            }
        }
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

    private PluginDescriptor toDescriptor(JlShellPlugin plugin) {
        PluginCompatibility.Result compatibility = PluginCompatibility.evaluate(hostVersion,
                plugin.minHostVersionInclusive(),
                plugin.maxHostVersionInclusive());
        return new PluginDescriptor(
                plugin.id(),
                plugin.displayName(),
                plugin.version(),
                plugin.author(),
                plugin.description(),
                plugin.requiresSshSession(),
                plugin.minHostVersionInclusive(),
                plugin.maxHostVersionInclusive(),
                compatibility.status(),
                compatibility.warning(),
                plugin
        );
    }
}
