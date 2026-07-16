package com.jlshell.plugin.loader.store;

import com.jlshell.plugin.api.PluginScope;
import java.util.Locale;

/** 搜索条件；空值表示不传给服务端。 */
public record PluginStoreSearch(String query, PluginScope scope, String hostVersion,
                                Locale locale, int page, int size, Sort sort) {
    public enum Sort { UPDATED, DOWNLOADS, NAME }

    public PluginStoreSearch {
        page = Math.max(page, 0);
        size = Math.clamp(size, 1, 100);
        sort = sort == null ? Sort.UPDATED : sort;
    }

    public static PluginStoreSearch initial(String hostVersion, Locale locale) {
        return new PluginStoreSearch(null, null, hostVersion, locale, 0, 20, Sort.UPDATED);
    }
}
