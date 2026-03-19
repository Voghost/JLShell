package com.jlshell.ui.model;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;

/**
 * 连接列表项模型。
 */
public record ConnectionProfile(
        String id,
        String displayName,
        String host,
        int port,
        String username,
        AuthenticationType authenticationType,
        HostKeyVerificationMode hostKeyVerificationMode,
        String description,
        String defaultRemotePath,
        boolean favorite,
        String projectId,
        ConnectionType connectionType,
        String folderId
) {

    public String summary() {
        if (connectionType == ConnectionType.LOCAL_SHELL) {
            return "Local Shell";
        }
        return username + "@" + host + ":" + port;
    }
}
