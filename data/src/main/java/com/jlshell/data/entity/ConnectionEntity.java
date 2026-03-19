package com.jlshell.data.entity;

import java.util.ArrayList;
import java.util.List;

import com.jlshell.core.model.ConnectionType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * SSH 连接配置实体。
 */
@Entity
@Table(name = "connections")
public class ConnectionEntity extends AbstractAuditableEntity {

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private int port = 22;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_type", nullable = false, length = 32)
    private AuthenticationType authenticationType;

    @Column(name = "host_key_verification_mode", nullable = false, length = 64)
    private String hostKeyVerificationMode = "STRICT";

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "default_remote_path", length = 1024)
    private String defaultRemotePath;

    @Column(name = "favorite", nullable = false)
    private boolean favorite;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = true, length = 32)
    private ConnectionType connectionType = ConnectionType.SSH;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = true)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = true)
    private ConnectionFolderEntity folder;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true, optional = true)
    @JoinColumn(name = "credential_id", nullable = true, unique = true)
    private CredentialEntity credential;

    @OneToMany(mappedBy = "connection", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SessionHistoryEntity> sessionHistories = new ArrayList<>();

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(AuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getHostKeyVerificationMode() {
        return hostKeyVerificationMode;
    }

    public void setHostKeyVerificationMode(String hostKeyVerificationMode) {
        this.hostKeyVerificationMode = hostKeyVerificationMode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultRemotePath() {
        return defaultRemotePath;
    }

    public void setDefaultRemotePath(String defaultRemotePath) {
        this.defaultRemotePath = defaultRemotePath;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(ConnectionType connectionType) {
        this.connectionType = connectionType;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public ConnectionFolderEntity getFolder() {
        return folder;
    }

    public void setFolder(ConnectionFolderEntity folder) {
        this.folder = folder;
    }

    public CredentialEntity getCredential() {
        return credential;
    }

    public void setCredential(CredentialEntity credential) {
        this.credential = credential;
        if (credential != null) {
            credential.setConnection(this);
        }
    }

    public List<SessionHistoryEntity> getSessionHistories() {
        return sessionHistories;
    }
}
