package com.jlshell.data.entity;

import com.jlshell.core.model.ConnectionType;

/**
 * SSH 连接配置实体（纯 POJO，由 JDBI 映射）。
 */
public class ConnectionEntity extends AbstractAuditableEntity {

    private String displayName;
    private String host;
    private int port = 22;
    private String username;
    private AuthenticationType authenticationType;
    private String hostKeyVerificationMode = "STRICT";
    private String description;
    private String defaultRemotePath;
    private boolean favorite;
    private ConnectionType connectionType = ConnectionType.SSH;

    // FK 字段，由 JDBI 直接映射（列名: project_id, folder_id, credential_id）
    private String projectId;
    private String folderId;
    private String credentialId;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }

    public String getHostKeyVerificationMode() { return hostKeyVerificationMode; }
    public void setHostKeyVerificationMode(String hostKeyVerificationMode) { this.hostKeyVerificationMode = hostKeyVerificationMode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDefaultRemotePath() { return defaultRemotePath; }
    public void setDefaultRemotePath(String defaultRemotePath) { this.defaultRemotePath = defaultRemotePath; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public ConnectionType getConnectionType() { return connectionType; }
    public void setConnectionType(ConnectionType connectionType) { this.connectionType = connectionType; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getFolderId() { return folderId; }
    public void setFolderId(String folderId) { this.folderId = folderId; }

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
}
