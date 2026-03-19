package com.jlshell.plugin.loader;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginLoaderConfiguration {

    private final PluginManager pluginManager;

    public PluginLoaderConfiguration(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @PostConstruct
    public void init() {
        pluginManager.loadPlugins();
    }
}
