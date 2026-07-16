package com.jlshell.plugin.loader;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.plugin.api.PluginScope;

/**
 * 持久化插件启用状态。只存储被停用的插件 ID，因此新安装插件默认启用。
 */
public final class PluginEnablementService {

    private static final String SESSION_KEY = "plugins.disabled.session";
    private static final String PROGRAM_KEY = "plugins.disabled.program";

    private final AppSettingsService settings;
    private final Gson gson = new Gson();
    private final EnumMap<PluginScope, Set<String>> disabled = new EnumMap<>(PluginScope.class);

    /** 测试或独立使用时提供仅内存实现。 */
    public PluginEnablementService() {
        this(null);
    }

    public PluginEnablementService(AppSettingsService settings) {
        this.settings = settings;
        disabled.put(PluginScope.SESSION, read(PluginScope.SESSION));
        disabled.put(PluginScope.PROGRAM, read(PluginScope.PROGRAM));
    }

    public synchronized boolean isEnabled(String pluginId, PluginScope scope) {
        return !disabled.get(scope).contains(pluginId);
    }

    public synchronized void setEnabled(String pluginId, PluginScope scope, boolean enabled) {
        if (pluginId == null || pluginId.isBlank()) return;
        Set<String> ids = disabled.get(scope);
        boolean changed = enabled ? ids.remove(pluginId) : ids.add(pluginId);
        if (changed) persist(scope, ids);
    }

    public synchronized Set<String> disabledPluginIds(PluginScope scope) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(disabled.get(scope)));
    }

    private Set<String> read(PluginScope scope) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (settings == null) return result;
        String raw = settings.get(key(scope), "[]");
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonArray()) return result;
            parsed.getAsJsonArray().forEach(value -> {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String id = value.getAsString().strip();
                    if (!id.isEmpty()) result.add(id);
                }
            });
        } catch (RuntimeException ignored) {
            // 损坏的旧配置按空集合处理，下一次变更时会自动覆盖为合法 JSON。
        }
        return result;
    }

    private void persist(PluginScope scope, Set<String> ids) {
        if (settings != null) settings.set(key(scope), gson.toJson(ids));
    }

    private static String key(PluginScope scope) {
        return scope == PluginScope.PROGRAM ? PROGRAM_KEY : SESSION_KEY;
    }
}
