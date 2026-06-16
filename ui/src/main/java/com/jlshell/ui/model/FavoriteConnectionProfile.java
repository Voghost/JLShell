package com.jlshell.ui.model;

import com.jlshell.core.model.ConnectionType;

/**
 * 收藏连接列表项模型。
 */
public record FavoriteConnectionProfile(
        String id,
        String displayName,
        String host,
        int port,
        String username,
        ConnectionType connectionType,
        String folderPath
) {

    public String hostInfo() {
        if (connectionType == ConnectionType.LOCAL_SHELL) return "Local Shell";
        return host + ":" + port;
    }
}
