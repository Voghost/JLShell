package com.jlshell.ui.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.ConnectionEntity;
import com.jlshell.data.entity.CredentialEntity;
import com.jlshell.data.entity.ProjectEntity;
import com.jlshell.data.entity.SessionHistoryEntity;
import com.jlshell.data.repository.ConnectionRepository;
import com.jlshell.data.repository.ProjectRepository;
import com.jlshell.data.repository.SessionHistoryRepository;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.data.entity.ConnectionFolderEntity;
import com.jlshell.data.repository.ConnectionFolderRepository;
import com.jlshell.ui.model.FolderProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接配置与会话历史应用服务。
 */
@Service
@Transactional
public class ConnectionProfileService {

    private final ConnectionRepository connectionRepository;
    private final SessionHistoryRepository sessionHistoryRepository;
    private final ProjectRepository projectRepository;
    private final ConnectionFolderRepository folderRepository;

    public ConnectionProfileService(
            ConnectionRepository connectionRepository,
            SessionHistoryRepository sessionHistoryRepository,
            ProjectRepository projectRepository,
            ConnectionFolderRepository folderRepository
    ) {
        this.connectionRepository = connectionRepository;
        this.sessionHistoryRepository = sessionHistoryRepository;
        this.projectRepository = projectRepository;
        this.folderRepository = folderRepository;
    }

    // ── Connection queries ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConnectionProfile> listProfiles() {
        return connectionRepository.findAllByOrderByDisplayNameAsc()
                .stream()
                .map(this::toProfile)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConnectionProfile> listProfilesByProject(String projectId) {
        if (projectId == null) {
            return connectionRepository.findAllByProjectIsNullOrderByDisplayNameAsc()
                    .stream().map(this::toProfile).toList();
        }
        return connectionRepository.findAllByProject_IdOrderByDisplayNameAsc(projectId)
                .stream().map(this::toProfile).toList();
    }

    @Transactional(readOnly = true)
    public ConnectionFormData loadForm(String id) {
        ConnectionEntity entity = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + id));
        return new ConnectionFormData(
                entity.getId(),
                entity.getDisplayName(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getAuthenticationType(),
                entity.getCredential() != null ? entity.getCredential().getEncryptedPassword() : null,
                entity.getCredential() != null ? entity.getCredential().getPrivateKeyPath() : null,
                entity.getCredential() != null ? entity.getCredential().getEncryptedPassphrase() : null,
                HostKeyVerificationMode.valueOf(entity.getHostKeyVerificationMode()),
                entity.getDescription(),
                entity.getDefaultRemotePath(),
                entity.isFavorite(),
                entity.getProject() != null ? entity.getProject().getId() : null,
                entity.getConnectionType() != null ? entity.getConnectionType() : ConnectionType.SSH,
                entity.getFolder() != null ? entity.getFolder().getId() : null
        );
    }

    public ConnectionProfile save(ConnectionFormData formData) {
        ConnectionType connType = formData.connectionType() != null ? formData.connectionType() : ConnectionType.SSH;
        if (connType == ConnectionType.SSH) {
            validate(formData);
        }

        ConnectionEntity entity = formData.id() == null || formData.id().isBlank()
                ? new ConnectionEntity()
                : connectionRepository.findById(formData.id())
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + formData.id()));

        entity.setConnectionType(connType);
        entity.setDisplayName(formData.displayName());
        entity.setFavorite(formData.favorite());
        entity.setDescription(blankToNull(formData.description()));

        if (connType == ConnectionType.SSH) {
            CredentialEntity credential = entity.getCredential() == null ? new CredentialEntity() : entity.getCredential();
            credential.setAuthenticationType(formData.authenticationType());
            credential.setEncryptedPassword(blankToNull(formData.password()));
            credential.setPrivateKeyPath(blankToNull(formData.privateKeyPath()));
            credential.setEncryptedPassphrase(blankToNull(formData.passphrase()));

            entity.setHost(formData.host());
            entity.setPort(formData.port());
            entity.setUsername(formData.username());
            entity.setAuthenticationType(formData.authenticationType());
            entity.setHostKeyVerificationMode(formData.hostKeyVerificationMode().name());
            entity.setDefaultRemotePath(blankToNull(formData.defaultRemotePath()));
            entity.setCredential(credential);
        } else {
            // LOCAL_SHELL: clear SSH-specific fields; keep a stub credential to satisfy NOT NULL FK
            entity.setHost("");
            entity.setPort(0);
            entity.setUsername("");
            entity.setAuthenticationType(AuthenticationType.PASSWORD);
            entity.setHostKeyVerificationMode(HostKeyVerificationMode.STRICT.name());
            entity.setDefaultRemotePath(null);
            CredentialEntity stub = entity.getCredential() == null ? new CredentialEntity() : entity.getCredential();
            stub.setAuthenticationType(AuthenticationType.PASSWORD);
            stub.setEncryptedPassword(null);
            stub.setPrivateKeyPath(null);
            stub.setEncryptedPassphrase(null);
            entity.setCredential(stub);
        }

        if (formData.projectId() != null && !formData.projectId().isBlank()) {
            entity.setProject(projectRepository.getReferenceById(formData.projectId()));
        } else {
            entity.setProject(null);
        }

        if (formData.folderId() != null && !formData.folderId().isBlank()) {
            entity.setFolder(folderRepository.getReferenceById(formData.folderId()));
        } else {
            entity.setFolder(null);
        }

        return toProfile(connectionRepository.save(entity));
    }

    public void delete(String id) {
        connectionRepository.deleteById(id);
    }

    // ── Folder CRUD ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FolderProfile> listFolders(String projectId) {
        List<ConnectionFolderEntity> entities = projectId == null
                ? folderRepository.findAllByProjectIsNullOrderBySortOrderAscNameAsc()
                : folderRepository.findAllByProject_IdOrderBySortOrderAscNameAsc(projectId);
        return entities.stream().map(this::toFolderProfile).toList();
    }

    public FolderProfile saveFolder(String id, String name, String parentId, String projectId) {
        ConnectionFolderEntity entity = id == null || id.isBlank()
                ? new ConnectionFolderEntity()
                : folderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));
        entity.setName(name);
        if (parentId != null && !parentId.isBlank()) {
            ConnectionFolderEntity parent = folderRepository.getReferenceById(parentId);
            entity.setParent(parent);
        } else {
            entity.setParent(null);
        }
        entity.setProject(projectId != null && !projectId.isBlank()
                ? projectRepository.getReferenceById(projectId) : null);
        return toFolderProfile(folderRepository.save(entity));
    }

    /** 计算某文件夹在树中的深度（根层为 0）。 */
    public int getFolderDepth(String folderId) {
        int depth = 0;
        String current = folderId;
        while (true) {
            String finalCurrent = current;
            ConnectionFolderEntity entity = folderRepository.findById(finalCurrent).orElse(null);
            if (entity == null || entity.getParent() == null) break;
            current = entity.getParent().getId();
            depth++;
            if (depth > 100) break; // 防止循环引用死循环
        }
        return depth;
    }

    public FolderProfile renameFolder(String id, String newName) {
        ConnectionFolderEntity entity = folderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));
        entity.setName(newName);
        return toFolderProfile(folderRepository.save(entity));
    }

    /** 将连接移动到指定文件夹（targetFolderId 为 null 表示移到根层）。 */
    @Transactional
    public void moveConnectionToFolder(String connectionId, String targetFolderId) {
        ConnectionEntity entity = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));
        entity.setFolder(targetFolderId != null && !targetFolderId.isBlank()
                ? folderRepository.getReferenceById(targetFolderId) : null);
        connectionRepository.save(entity);
    }

    /** 将文件夹移动到另一个文件夹下（targetParentId 为 null 表示移到根层）。 */
    @Transactional
    public void moveFolderToParent(String folderId, String targetParentId) {
        ConnectionFolderEntity entity = folderRepository.findById(folderId)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + folderId));
        entity.setParent(targetParentId != null && !targetParentId.isBlank()
                ? folderRepository.getReferenceById(targetParentId) : null);
        folderRepository.save(entity);
    }

    public void deleteFolder(String id) {
        // Move connections in this folder to no folder
        connectionRepository.findAllByFolder_IdOrderByDisplayNameAsc(id)
                .forEach(c -> { c.setFolder(null); connectionRepository.save(c); });
        folderRepository.deleteById(id);
    }

    // ── Project CRUD ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProjectProfile> listProjects() {
        return projectRepository.findAllByOrderBySortOrderAscNameAsc()
                .stream().map(this::toProjectProfile).toList();
    }

    public ProjectProfile saveProject(String id, String name, String description) {
        ProjectEntity entity = id == null || id.isBlank()
                ? new ProjectEntity()
                : projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        entity.setName(name);
        entity.setDescription(description);
        return toProjectProfile(projectRepository.save(entity));
    }

    public void deleteProject(String id) {
        // Null out project FK on connections before deleting
        connectionRepository.findAllByProject_IdOrderByDisplayNameAsc(id)
                .forEach(c -> { c.setProject(null); connectionRepository.save(c); });
        projectRepository.deleteById(id);
    }

    // ── Session history ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConnectionRequest toConnectionRequest(String connectionId) {
        ConnectionEntity entity = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));

        CredentialPayload credentialPayload = entity.getAuthenticationType() == AuthenticationType.PASSWORD
                ? CredentialPayload.forPassword(value(entity.getCredential().getEncryptedPassword()).toCharArray())
                : CredentialPayload.forPrivateKey(
                Path.of(value(entity.getCredential().getPrivateKeyPath())),
                value(entity.getCredential().getEncryptedPassphrase()).toCharArray()
        );

        return new ConnectionRequest(
                entity.getDisplayName(),
                new ConnectionTarget(entity.getHost(), entity.getPort(), entity.getUsername(), null, null),
                entity.getAuthenticationType() == AuthenticationType.PASSWORD
                        ? AuthenticationMethod.PASSWORD
                        : AuthenticationMethod.PRIVATE_KEY,
                credentialPayload,
                HostKeyVerificationMode.valueOf(entity.getHostKeyVerificationMode())
        );
    }

    public String recordSessionOpened(SessionId sessionId, String connectionId, String remoteAddress) {
        SessionHistoryEntity history = new SessionHistoryEntity();
        history.setConnection(connectionRepository.getReferenceById(connectionId));
        history.setSessionIdentifier(sessionId.toString());
        history.setState(SessionState.CONNECTED.name());
        history.setOpenedAt(Instant.now());
        history.setRemoteAddress(remoteAddress);
        return sessionHistoryRepository.save(history).getId();
    }

    public void recordSessionClosed(String historyId, SessionState state, Integer exitCode, String failureReason) {
        SessionHistoryEntity history = sessionHistoryRepository.findById(historyId)
                .orElseThrow(() -> new IllegalArgumentException("Session history not found: " + historyId));
        history.setState(state.name());
        history.setClosedAt(Instant.now());
        history.setExitCode(exitCode);
        history.setFailureReason(blankToNull(failureReason));
        sessionHistoryRepository.save(history);
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
                entity.getProject() != null ? entity.getProject().getId() : null,
                entity.getConnectionType() != null ? entity.getConnectionType() : ConnectionType.SSH,
                entity.getFolder() != null ? entity.getFolder().getId() : null
        );
    }

    private FolderProfile toFolderProfile(ConnectionFolderEntity entity) {
        return new FolderProfile(
                entity.getId(),
                entity.getName(),
                entity.getParent() != null ? entity.getParent().getId() : null,
                entity.getProject() != null ? entity.getProject().getId() : null,
                entity.getSortOrder()
        );
    }

    private ProjectProfile toProjectProfile(ProjectEntity entity) {
        return new ProjectProfile(entity.getId(), entity.getName(), entity.getDescription());
    }

    private void validate(ConnectionFormData formData) {
        if (isBlank(formData.host()) || isBlank(formData.username())) {
            throw new IllegalArgumentException("Host and username are required");
        }
        if (formData.authenticationType() == AuthenticationType.PASSWORD && isBlank(formData.password())) {
            throw new IllegalArgumentException("Password is required for password authentication");
        }
        if (formData.authenticationType() == AuthenticationType.PRIVATE_KEY && isBlank(formData.privateKeyPath())) {
            throw new IllegalArgumentException("Private key path is required for key authentication");
        }
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
