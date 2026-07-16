package com.jlshell.plugin.loader.store;

import java.util.List;

/** Spring Page 响应的客户端表示。 */
public record PluginStorePage(List<PluginStoreListing> content, int number, int size,
                              long totalElements, int totalPages) {
    public PluginStorePage {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
