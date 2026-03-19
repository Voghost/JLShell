package com.jlshell.ui.model;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;

/**
 * 连接编辑表单模型。
 */
public record ConnectionFormData(
        String id,
        String displayName,
        String host,
        int port,
        String username,
        AuthenticationType authenticationType,
        String password,
        String privateKeyPath,
        String passphrase,
        HostKeyVerificationMode hostKeyVerificationMode,
        String description,
        String defaultRemotePath,
        boolean favorite,
        String projectId,
        ConnectionType connectionType,
        String folderId
) {

    public ConnectionFormData {
        port = port <= 0 ? 22 : port;
        authenticationType = authenticationType == null ? AuthenticationType.PASSWORD : authenticationType;
        hostKeyVerificationMode = hostKeyVerificationMode == null
                ? HostKeyVerificationMode.STRICT
                : hostKeyVerificationMode;
        connectionType = connectionType == null ? ConnectionType.SSH : connectionType;
    }

    public static ConnectionFormData empty() {
        return new ConnectionFormData(
                null, "", "", 22, "",
                AuthenticationType.PASSWORD, "", "", "",
                HostKeyVerificationMode.STRICT, "", "", false, null,
                ConnectionType.SSH, null
        );
    }
}
