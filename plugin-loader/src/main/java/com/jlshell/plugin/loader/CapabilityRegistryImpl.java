package com.jlshell.plugin.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityHandler;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.rpc.CapabilitySpec;

/**
 * 每会话能力注册表。按 (pluginId, name) 存储与查找。
 * 线程安全：内部用 ConcurrentHashMap。
 */
public class CapabilityRegistryImpl implements CapabilityRegistry {

    /** key = pluginId + "/" + capabilityName */
    private final ConcurrentHashMap<String, Capability> byKey = new ConcurrentHashMap<>();

    private static String key(String pluginId, String name) {
        return pluginId + "/" + name;
    }

    /** 真正的注册入口：host 用插件 id 注入。 */
    public void register(String pluginId, Capability capability) {
        if (pluginId == null) throw new IllegalArgumentException("pluginId required");
        String name = capability.spec().name();
        byKey.put(key(pluginId, name), withPluginId(capability, pluginId));
    }

    /** 接口实现：要求 capability 已带 pluginId（由 host view 注入）。 */
    @Override
    public void register(Capability capability) {
        register(capability.pluginId(), capability);
    }

    @Override
    public void unregister(String name) {
        byKey.entrySet().removeIf(e -> e.getKey().endsWith("/" + name));
    }

    public void unregister(String pluginId, String name) {
        byKey.remove(key(pluginId, name));
    }

    /** 停用插件时清掉它的全部能力。 */
    public void clearForPlugin(String pluginId) {
        byKey.entrySet().removeIf(e -> e.getKey().startsWith(pluginId + "/"));
    }

    public Optional<Capability> resolve(String pluginId, String name) {
        return Optional.ofNullable(byKey.get(key(pluginId, name)));
    }

    @Override
    public Optional<Capability> resolve(String name) {
        return byKey.values().stream().filter(c -> c.spec().name().equals(name)).findFirst();
    }

    @Override
    public List<CapabilitySpec> specs() {
        List<CapabilitySpec> out = new ArrayList<>();
        byKey.forEach((k, c) -> out.add(c.spec()));
        return out;
    }

    /** 列出能力清单（含 pluginId，供外部自省/MCP）。 */
    public List<CapabilitySpec> specs(String pluginId) {
        return byKey.entrySet().stream()
                .filter(e -> e.getKey().startsWith(pluginId + "/"))
                .map(e -> e.getValue().spec())
                .toList();
    }

    public List<Capability> capabilities() {
        return List.copyOf(byKey.values());
    }

    /** 清空全部能力。Task 4 的 deactivateAll 用。 */
    public void clear() {
        byKey.clear();
    }

    private static Capability withPluginId(Capability cap, String pluginId) {
        if (pluginId.equals(cap.pluginId())) return cap;
        CapabilitySpec s = cap.spec();
        return new Capability() {
            @Override public String pluginId() { return pluginId; }
            @Override public CapabilitySpec spec() { return s; }
            @Override public CapabilityHandler handler() { return cap.handler(); }
        };
    }
}
