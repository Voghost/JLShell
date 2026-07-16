package com.jlshell.plugin.loader.store;

import java.io.InputStream;

@FunctionalInterface
interface PluginPackageDownloader {
    InputStream download(String pluginId, String version) throws Exception;
}
