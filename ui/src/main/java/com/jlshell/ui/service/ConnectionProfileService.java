package com.jlshell.ui.service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.data.dao.ConnectionDao;
import com.jlshell.data.dao.ConnectionFolderDao;
import com.jlshell.data.dao.CredentialDao;
import com.jlshell.data.dao.ProjectDao;
import com.jlshell.data.dao.SessionHistoryDao;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.ConnectionEntity;
import com.jlshell.data.entity.ConnectionFolderEntity;
import com.jlshell.data.entity.CredentialEntity;
import com.jlshell.data.entity.ProjectEntity;
import com.jlshell.data.entity.RecentSessionEntry;
import com.jlshell.data.entity.SessionHistoryEntity;
import com.jlshell.plugin.api.event.ConnectionCreatedEvent;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectDeletedEvent;
import com.jlshell.plugin.loader.PluginRuntimeServices;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FavoriteConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.RecentSessionProfile;
import org.jdbi.v3.core.Jdbi;

/**
 * 连接配置与会话历史应用服务（基于 JDBI）。
 */
public class ConnectionProfileService {

    private final Jdbi jdbi;
    private final CredentialCipher credentialCipher;
    private final AppSettingsService appSettings;
    private final VaultService vaultService;

    public ConnectionProfileService(Jdbi jdbi, CredentialCipher credentialCipher,
                                     AppSettingsService appSettings, VaultService vaultService) {
        this.jdbi = jdbi;
        this.credentialCipher = credentialCipher;
        this.appSettings = appSettings;
        this.vaultService = vaultService;
    }

    // ── Connection queries ────────────────────────────────────────────

    public List<ConnectionProfile> listProfiles() {
        return jdbi.withHandle(h ->
                h.attach(ConnectionDao.class).findAllOrderByName()
                        .stream().map(this::toProfile).toList()
        );
    }

    public List<ConnectionProfile> listProfilesByProject(String projectId) {
        return jdbi.withHandle(h -> {
            ConnectionDao dao = h.attach(ConnectionDao.class);
            List<ConnectionEntity> entities = projectId == null
                    ? dao.findAllWithNoProject()
                    : dao.findAllByProjectId(projectId);
            return entities.stream().map(this::toProfile).toList();
        });
    }

    public ConnectionFormData loadForm(String id) {
        return jdbi.withHandle(h -> {
            ConnectionEntity entity = h.attach(ConnectionDao.class).findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));

            String vaultEntryId = entity.getVaultEntryId();
            String plainPassword;
            String plainPassphrase;
            String privateKeyPath;
            String keyContent = null;
            AuthenticationType authType;

            if (vaultEntryId != null) {
                // 从凭据库加载
                com.jlshell.ui.model.VaultEntryFormData vaultForm = vaultService.loadForm(vaultEntryId);
                authType = vaultForm.authenticationType();
                plainPassword = vaultForm.password();
                plainPassphrase = vaultForm.passphrase();
                privateKeyPath = vaultForm.privateKeyPath();
                keyContent = vaultForm.keyContent();
            } else {
                // 从旧凭证加载
                CredentialEntity credential = entity.getCredentialId() != null
                        ? h.attach(CredentialDao.class).findById(entity.getCredentialId()).orElse(null)
                        : null;
                plainPassword = decryptOrNull(credential != null ? credential.getEncryptedPassword() : null);
                plainPassphrase = decryptOrNull(credential != null ? credential.getEncryptedPassphrase() : null);
                privateKeyPath = credential != null ? credential.getPrivateKeyPath() : null;
                authType = credential != null ? credential.getAuthenticationType() : null;
            }

            return new ConnectionFormData(
                    entity.getId(),
                    entity.getDisplayName(),
                    entity.getHost(),
                    entity.getPort(),
                    entity.getUsername(),
                    authType != null ? authType : AuthenticationType.PASSWORD,
                    plainPassword,
                    privateKeyPath,
                    plainPassphrase,
                    HostKeyVerificationMode.valueOf(entity.getHostKeyVerificationMode()),
                    entity.getDescription(),
                    entity.getDefaultRemotePath(),
                    entity.isFavorite(),
                    entity.getProjectId(),
                    entity.getConnectionType() != null ? entity.getConnectionType() : ConnectionType.SSH,
                    entity.getFolderId(),
                    vaultEntryId,
                    keyContent
            );
        });
    }

    public ConnectionProfile save(ConnectionFormData formData) {
        ConnectionType connType = formData.connectionType() != null ? formData.connectionType() : ConnectionType.SSH;
        if (connType == ConnectionType.SSH) {
            validate(formData);
        }
        return doSave(formData);
    }

    /**
     * 导入用：跳过密码/私钥的非空校验（导入时密码通常留空，用户后续补）。
     * 仍要求 host + username 非空。只走 SSH 类型。
     */
    public ConnectionProfile saveImported(ConnectionFormData formData) {
        if (isBlank(formData.host()) || isBlank(formData.username())) {
            throw new IllegalArgumentException("Host and username are required");
        }
        ConnectionFormData sshForm = formData.connectionType() == null
                ? new ConnectionFormData(formData.id(), formData.displayName(), formData.host(), formData.port(),
                        formData.username(), formData.authenticationType(), formData.password(),
                        formData.privateKeyPath(), formData.passphrase(), formData.hostKeyVerificationMode(),
                        formData.description(), formData.defaultRemotePath(), formData.favorite(),
                        formData.projectId(), ConnectionType.SSH, formData.folderId(),
                        formData.vaultEntryId(), formData.keyContent())
                : formData;
        return doSave(sshForm);
    }

    private ConnectionProfile doSave(ConnectionFormData formData) {
        ConnectionType connType = formData.connectionType() != null ? formData.connectionType() : ConnectionType.SSH;
        boolean creating = formData.id() == null || formData.id().isBlank();

        ConnectionProfile saved = jdbi.inTransaction(h -> {
            ConnectionDao connDao = h.attach(ConnectionDao.class);
            CredentialDao credDao = h.attach(CredentialDao.class);

            boolean isNew = formData.id() == null || formData.id().isBlank();
            ConnectionEntity entity = isNew
                    ? new ConnectionEntity()
                    : connDao.findById(formData.id())
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + formData.id()));

            entity.setConnectionType(connType);
            String displayName = formData.displayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = formData.host();
            }
            entity.setDisplayName(displayName);
            entity.setFavorite(formData.favorite());
            entity.setDescription(blankToNull(formData.description()));
            entity.setProjectId(blankToNull(formData.projectId()));
            entity.setFolderId(blankToNull(formData.folderId()));

            if (connType == ConnectionType.SSH) {
                entity.setHost(formData.host());
                entity.setPort(formData.port());
                entity.setUsername(formData.username());
                entity.setAuthenticationType(formData.authenticationType());
                entity.setHostKeyVerificationMode(formData.hostKeyVerificationMode().name());
                entity.setDefaultRemotePath(blankToNull(formData.defaultRemotePath()));

                // 设置凭据库引用
                entity.setVaultEntryId(blankToNull(formData.vaultEntryId()));

                if (formData.vaultEntryId() != null && !formData.vaultEntryId().isBlank()) {
                    // 使用凭据库：不需要创建 per-connection CredentialEntity
                    entity.setCredentialId(null);
                } else {
                    // 手动输入：创建/更新 per-connection CredentialEntity
                    CredentialEntity credential = isNew || entity.getCredentialId() == null
                            ? new CredentialEntity()
                            : credDao.findById(entity.getCredentialId()).orElse(new CredentialEntity());

                    credential.setAuthenticationType(formData.authenticationType());
                    credential.setEncryptedPassword(encryptOrNull(blankToNull(formData.password())));
                    credential.setPrivateKeyPath(blankToNull(formData.privateKeyPath()));
                    credential.setEncryptedPassphrase(encryptOrNull(blankToNull(formData.passphrase())));

                    if (credential.getId() == null) {
                        credential.prepareInsert();
                        credDao.insert(credential);
                    } else {
                        credential.prepareUpdate();
                        credDao.update(credential);
                    }
                    entity.setCredentialId(credential.getId());
                }
            } else {
                // LOCAL_SHELL：清空 SSH 专用字段，保留一个存根凭证
                entity.setHost("");
                entity.setPort(0);
                entity.setUsername("");
                entity.setAuthenticationType(AuthenticationType.PASSWORD);
                entity.setHostKeyVerificationMode(HostKeyVerificationMode.STRICT.name());
                entity.setDefaultRemotePath(null);

                if (entity.getCredentialId() == null) {
                    CredentialEntity stub = new CredentialEntity();
                    stub.setAuthenticationType(AuthenticationType.PASSWORD);
                    stub.prepareInsert();
                    credDao.insert(stub);
                    entity.setCredentialId(stub.getId());
                }
            }

            if (isNew) {
                entity.prepareInsert();
                connDao.insert(entity);
            } else {
                entity.prepareUpdate();
                connDao.update(entity);
            }

            return toProfile(entity);
        });
        if (creating) {
            PluginRuntimeServices.publish(new ConnectionCreatedEvent(
                    saved.id(), saved.projectId(), saved.displayName(), saved.host(), saved.port(), Instant.now()));
        }
        return saved;
    }

    public void delete(String id) {
        jdbi.useHandle(h -> h.attach(ConnectionDao.class).deleteById(id));
    }

    // ── Folder CRUD ───────────────────────────────────────────────────

    public List<FolderProfile> listFolders(String projectId) {
        return jdbi.withHandle(h -> {
            ConnectionFolderDao dao = h.attach(ConnectionFolderDao.class);
            List<ConnectionFolderEntity> entities = projectId == null
                    ? dao.findAllWithNoProject()
                    : dao.findAllByProjectId(projectId);
            return entities.stream().map(this::toFolderProfile).toList();
        });
    }

    public FolderProfile saveFolder(String id, String name, String parentId, String projectId) {
        return jdbi.inTransaction(h -> {
            ConnectionFolderDao dao = h.attach(ConnectionFolderDao.class);
            boolean isNew = id == null || id.isBlank();
            ConnectionFolderEntity entity = isNew
                    ? new ConnectionFolderEntity()
                    : dao.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));

            entity.setName(name);
            entity.setParentId(blankToNull(parentId));
            entity.setProjectId(blankToNull(projectId));

            if (isNew) {
                entity.prepareInsert();
                dao.insert(entity);
            } else {
                entity.prepareUpdate();
                dao.update(entity);
            }
            return toFolderProfile(entity);
        });
    }

    /** 计算某文件夹在树中的深度（根层为 0）。 */
    public int getFolderDepth(String folderId) {
        return jdbi.withHandle(h -> {
            ConnectionFolderDao dao = h.attach(ConnectionFolderDao.class);
            int depth = 0;
            String current = folderId;
            while (true) {
                String finalCurrent = current;
                ConnectionFolderEntity entity = dao.findById(finalCurrent).orElse(null);
                if (entity == null || entity.getParentId() == null) break;
                current = entity.getParentId();
                depth++;
                if (depth > 100) break;
            }
            return depth;
        });
    }

    public FolderProfile renameFolder(String id, String newName) {
        return jdbi.inTransaction(h -> {
            ConnectionFolderDao dao = h.attach(ConnectionFolderDao.class);
            ConnectionFolderEntity entity = dao.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));
            entity.setName(newName);
            entity.prepareUpdate();
            dao.update(entity);
            return toFolderProfile(entity);
        });
    }

    public void moveConnectionToFolder(String connectionId, String targetFolderId) {
        jdbi.useHandle(h -> h.attach(ConnectionDao.class)
                .updateFolderId(connectionId, blankToNull(targetFolderId), Instant.now()));
    }

    public void moveFolderToParent(String folderId, String targetParentId) {
        jdbi.useHandle(h -> h.attach(ConnectionFolderDao.class)
                .updateParentId(folderId, blankToNull(targetParentId), Instant.now()));
    }

    /** Collect all connection names under a folder (recursively, including subfolders). */
    public List<String> collectConnectionNamesUnderFolder(String folderId) {
        return jdbi.withHandle(h -> {
            List<String> names = new ArrayList<>();
            collectConnectionNamesRecursive(h, folderId, names);
            return names;
        });
    }

    private void collectConnectionNamesRecursive(org.jdbi.v3.core.Handle h, String folderId, List<String> names) {
        names.addAll(h.attach(ConnectionDao.class).findNamesByFolderId(folderId));
        List<String> childFolderIds = h.attach(ConnectionFolderDao.class).findChildIdsByParentId(folderId);
        for (String childId : childFolderIds) {
            collectConnectionNamesRecursive(h, childId, names);
        }
    }

    public void deleteFolder(String id) {
        jdbi.useTransaction(h -> {
            deleteFolderRecursive(h, id);
        });
    }

    private void deleteFolderRecursive(org.jdbi.v3.core.Handle h, String folderId) {
        List<String> childIds = h.attach(ConnectionFolderDao.class).findChildIdsByParentId(folderId);
        for (String childId : childIds) {
            deleteFolderRecursive(h, childId);
        }
        h.attach(ConnectionDao.class).clearFolderIdForFolder(folderId);
        h.attach(ConnectionFolderDao.class).deleteById(folderId);
    }

    public void deleteConnections(List<String> ids) {
        jdbi.useTransaction(h -> {
            ConnectionDao dao = h.attach(ConnectionDao.class);
            for (String id : ids) {
                dao.deleteById(id);
            }
        });
    }

    // ── Project CRUD ──────────────────────────────────────────────────

    public List<ProjectProfile> listProjects() {
        return jdbi.withHandle(h ->
                h.attach(ProjectDao.class).findAllOrderBySortOrderName()
                        .stream().map(this::toProjectProfile).toList()
        );
    }

    public ProjectProfile saveProject(String id, String name, String description) {
        boolean creating = id == null || id.isBlank();
        ProjectProfile saved = jdbi.inTransaction(h -> {
            ProjectDao dao = h.attach(ProjectDao.class);
            boolean isNew = id == null || id.isBlank();
            ProjectEntity entity = isNew
                    ? new ProjectEntity()
                    : dao.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));

            entity.setName(name);
            entity.setDescription(description);

            if (isNew) {
                entity.prepareInsert();
                dao.insert(entity);
            } else {
                entity.prepareUpdate();
                dao.update(entity);
            }
            return toProjectProfile(entity);
        });
        if (creating) {
            PluginRuntimeServices.publish(new ProjectCreatedEvent(
                    saved.id(), saved.name(), saved.description(), Instant.now()));
        }
        return saved;
    }

    public void deleteProject(String id) {
        jdbi.useTransaction(h -> {
            h.attach(ConnectionDao.class).clearProjectIdForProject(id);
            h.attach(ConnectionFolderDao.class).clearProjectIdForProject(id);
            vaultService.clearProjectIdForProject(id);
            h.attach(ProjectDao.class).deleteById(id);
        });
        PluginRuntimeServices.publish(new ProjectDeletedEvent(id, Instant.now()));
    }

    // ── Session history ───────────────────────────────────────────────

    public ConnectionRequest toConnectionRequest(String connectionId) {
        return jdbi.withHandle(h -> {
            ConnectionEntity entity = h.attach(ConnectionDao.class).findById(connectionId)
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));

            CredentialPayload credentialPayload;
            String vaultEntryId = entity.getVaultEntryId();

            if (vaultEntryId != null) {
                // 使用凭据库
                credentialPayload = vaultService.toCredentialPayload(vaultEntryId);
            } else {
                // 使用旧凭证
                CredentialEntity credential = entity.getCredentialId() != null
                        ? h.attach(CredentialDao.class).findById(entity.getCredentialId()).orElse(null)
                        : null;

                if (entity.getAuthenticationType() == AuthenticationType.PASSWORD) {
                    String pwd = decryptOrNull(credential != null ? credential.getEncryptedPassword() : null);
                    credentialPayload = CredentialPayload.forPassword(value(pwd).toCharArray());
                } else {
                    String keyPath = credential != null ? credential.getPrivateKeyPath() : null;
                    String passphrase = decryptOrNull(credential != null ? credential.getEncryptedPassphrase() : null);
                    credentialPayload = CredentialPayload.forPrivateKey(
                            Path.of(value(keyPath)),
                            value(passphrase).toCharArray()
                    );
                }
            }

            int timeoutSeconds = 10;
            try { timeoutSeconds = Integer.parseInt(appSettings.get("connection.timeout", "10")); }
            catch (NumberFormatException ignored) {}

            return new ConnectionRequest(
                    entity.getDisplayName(),
                    new ConnectionTarget(entity.getHost(), entity.getPort(), entity.getUsername(),
                            Duration.ofSeconds(timeoutSeconds), null),
                    entity.getAuthenticationType() == AuthenticationType.PASSWORD
                            ? AuthenticationMethod.PASSWORD
                            : AuthenticationMethod.PRIVATE_KEY,
                    credentialPayload,
                    HostKeyVerificationMode.valueOf(entity.getHostKeyVerificationMode())
            );
        });
    }

    public String recordSessionOpened(SessionId sessionId, String connectionId, String remoteAddress) {
        return jdbi.inTransaction(h -> {
            SessionHistoryEntity history = new SessionHistoryEntity();
            history.setConnectionId(connectionId);
            history.setSessionIdentifier(sessionId.toString());
            history.setState(SessionState.CONNECTED.name());
            history.setOpenedAt(Instant.now());
            history.setRemoteAddress(remoteAddress);
            history.prepareInsert();
            h.attach(SessionHistoryDao.class).insert(history);
            return history.getId();
        });
    }

    public void recordSessionClosed(String historyId, SessionState state, Integer exitCode, String failureReason) {
        jdbi.useTransaction(h -> {
            SessionHistoryDao dao = h.attach(SessionHistoryDao.class);
            SessionHistoryEntity history = dao.findById(historyId)
                    .orElseThrow(() -> new IllegalArgumentException("Session history not found: " + historyId));
            history.setState(state.name());
            history.setClosedAt(Instant.now());
            history.setExitCode(exitCode);
            history.setFailureReason(blankToNull(failureReason));
            history.prepareUpdate();
            dao.update(history);
        });
    }

    // ── Recent sessions & favorites ──────────────────────────────────

    public List<RecentSessionProfile> listRecentSessions(int limit) {
        return jdbi.withHandle(h -> {
            List<RecentSessionEntry> entries = h.attach(SessionHistoryDao.class)
                    .findRecentWithConnectionInfo(limit);
            return entries.stream().map(this::toRecentSessionProfile).toList();
        });
    }

    public List<FavoriteConnectionProfile> listFavoriteProfiles() {
        return jdbi.withHandle(h -> {
            List<ConnectionEntity> entities = h.attach(ConnectionDao.class).findAllFavorites();
            return entities.stream().map(entity -> {
                String folderPath = resolveFolderPathForFolderId(h, entity.getFolderId());
                return toFavoriteProfile(entity, folderPath);
            }).toList();
        });
    }

    // ── Mapping ───────────────────────────────────────────────────────

    private ConnectionProfile toProfile(ConnectionEntity entity) {
        String hkvm = entity.getHostKeyVerificationMode();
        return new ConnectionProfile(
                entity.getId(),
                entity.getDisplayName(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getAuthenticationType() != null ? entity.getAuthenticationType() : AuthenticationType.PASSWORD,
                hkvm != null && !hkvm.isBlank() ? HostKeyVerificationMode.valueOf(hkvm) : HostKeyVerificationMode.STRICT,
                entity.getDescription(),
                entity.getDefaultRemotePath(),
                entity.isFavorite(),
                entity.getProjectId(),
                entity.getConnectionType() != null ? entity.getConnectionType() : ConnectionType.SSH,
                entity.getFolderId(),
                entity.getVaultEntryId()
        );
    }

    private FolderProfile toFolderProfile(ConnectionFolderEntity entity) {
        return new FolderProfile(
                entity.getId(),
                entity.getName(),
                entity.getParentId(),
                entity.getProjectId(),
                entity.getSortOrder()
        );
    }

    private ProjectProfile toProjectProfile(ProjectEntity entity) {
        return new ProjectProfile(entity.getId(), entity.getName(), entity.getDescription());
    }

    private RecentSessionProfile toRecentSessionProfile(RecentSessionEntry entry) {
        return new RecentSessionProfile(
                entry.getConnectionId(),
                entry.getDisplayName(),
                entry.getHost(),
                entry.getPort(),
                entry.getUsername(),
                entry.getConnectionType(),
                entry.getOpenedAt(),
                entry.getClosedAt(),
                entry.getState()
        );
    }

    private FavoriteConnectionProfile toFavoriteProfile(ConnectionEntity entity, String folderPath) {
        return new FavoriteConnectionProfile(
                entity.getId(),
                entity.getDisplayName(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getConnectionType() != null ? entity.getConnectionType() : ConnectionType.SSH,
                folderPath
        );
    }

    private String resolveFolderPathForFolderId(org.jdbi.v3.core.Handle h, String folderId) {
        if (folderId == null) return null;
        ConnectionFolderDao folderDao = h.attach(ConnectionFolderDao.class);
        List<String> parts = new ArrayList<>();
        String current = folderId;
        while (current != null) {
            ConnectionFolderEntity entity = folderDao.findById(current).orElse(null);
            if (entity == null) break;
            parts.add(0, entity.getName());
            current = entity.getParentId();
            if (parts.size() > 20) break;
        }
        return String.join(" / ", parts);
    }

    private void validate(ConnectionFormData formData) {
        if (isBlank(formData.host()) || isBlank(formData.username())) {
            throw new IllegalArgumentException("Host and username are required");
        }
        // 使用凭据库时不验证密码/密钥（由 vault 保证）
        if (formData.vaultEntryId() != null && !formData.vaultEntryId().isBlank()) {
            return;
        }
        if (formData.authenticationType() == AuthenticationType.PASSWORD && isBlank(formData.password())) {
            throw new IllegalArgumentException("Password is required for password authentication");
        }
        if (formData.authenticationType() == AuthenticationType.PRIVATE_KEY && isBlank(formData.privateKeyPath())) {
            throw new IllegalArgumentException("Private key path is required for key authentication");
        }
    }

    private String encryptOrNull(String plainText) {
        return plainText != null ? credentialCipher.encrypt(plainText) : null;
    }

    private String decryptOrNull(String cipherText) {
        return cipherText != null ? credentialCipher.decrypt(cipherText) : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
