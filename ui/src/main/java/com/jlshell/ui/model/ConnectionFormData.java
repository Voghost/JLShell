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
        String folderId,
        String vaultEntryId,
        String keyContent
) {

    public ConnectionFormData {
        port = port <= 0 ? 22 : port;
        authenticationType = authenticationType == null ? AuthenticationType.PASSWORD : authenticationType;
        connectionType = connectionType == null ? ConnectionType.SSH : connectionType;
    }

    public static ConnectionFormData empty(String projectId) {
        return new ConnectionFormData(
                null, "", "", 22, "",
                AuthenticationType.PASSWORD, "", "", "",
                HostKeyVerificationMode.STRICT, "", "", false, projectId,
                ConnectionType.SSH, null, null, null
        );
    }

    public static ConnectionFormData emptyWithFolder(String projectId, String folderId) {
        return new ConnectionFormData(
                null, "", "", 22, "",
                AuthenticationType.PASSWORD, "", "", "",
                HostKeyVerificationMode.STRICT, "", "", false, projectId,
                ConnectionType.SSH, folderId, null, null
        );
    }
}
