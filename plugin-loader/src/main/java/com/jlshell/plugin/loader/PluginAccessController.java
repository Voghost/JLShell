package com.jlshell.plugin.loader;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.security.PluginAccessDecision;
import com.jlshell.plugin.api.security.PluginAccessPolicy;
import com.jlshell.plugin.api.security.PluginAccessPolicyProvider;
import com.jlshell.plugin.api.security.PluginAccessRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 统一的插件激活与能力调用权限决策器。默认允许，任一可信 provider 拒绝即拒绝。 */
public final class PluginAccessController implements PluginAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(PluginAccessController.class);

    private final ConcurrentHashMap<String, PluginAccessPolicyProvider> providers = new ConcurrentHashMap<>();

    public Registration registerTrusted(String pluginId, PluginAccessPolicyProvider provider) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(provider, "provider");
        providers.put(pluginId, provider);
        return () -> providers.remove(pluginId, provider);
    }

    public void unregister(String pluginId) {
        providers.remove(pluginId);
    }

    @Override
    public PluginAccessDecision evaluate(PluginAccessRequest request) {
        Objects.requireNonNull(request, "request");
        for (PluginAccessPolicyProvider provider : providers.values().stream()
                .sorted(Comparator.comparingInt(PluginAccessPolicyProvider::priority).reversed())
                .toList()) {
            PluginAccessDecision decision;
            try {
                decision = provider.evaluate(request);
            } catch (RuntimeException error) {
                log.error("Plugin access policy provider failed", error);
                return PluginAccessDecision.deny("access policy provider failed");
            }
            if (decision == null || decision.effect() == PluginAccessDecision.Effect.ABSTAIN) {
                continue;
            }
            if (decision.effect() == PluginAccessDecision.Effect.DENY) {
                return decision;
            }
        }
        return PluginAccessDecision.allow();
    }
}
