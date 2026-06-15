package com.jlshell.ui.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.data.dao.VaultEntryDao;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.data.entity.VaultEntryEntity;
import com.jlshell.ui.model.VaultEntryFormData;
import com.jlshell.ui.model.VaultEntryProfile;
import org.jdbi.v3.core.Jdbi;

/**
 * 凭据库应用服务。
 * 支持两种加密模式：SYSTEM（系统 master.key）和 CUSTOM（用户主密码派生密钥）。
 */
public class VaultService {

    private final Jdbi jdbi;
    private final CredentialCipher credentialCipher;
    private final VaultKeyService vaultKeyService;

    public VaultService(Jdbi jdbi, CredentialCipher credentialCipher, VaultKeyService vaultKeyService) {
        this.jdbi = jdbi;
        this.credentialCipher = credentialCipher;
        this.vaultKeyService = vaultKeyService;
    }

    public List<VaultEntryProfile> listByProject(String projectId) {
        return jdbi.withHandle(h -> {
            VaultEntryDao dao = h.attach(VaultEntryDao.class);
            List<VaultEntryEntity> entities = projectId == null
                    ? dao.findAllWithNoProject()
                    : dao.findAllByProjectId(projectId);
            return entities.stream().map(this::toProfile).toList();
        });
    }

    public List<VaultEntryProfile> listAll() {
        return jdbi.withHandle(h ->
                h.attach(VaultEntryDao.class).findAllOrderByName()
                        .stream().map(this::toProfile).toList()
        );
    }

    /**
     * 加载表单数据（解密敏感信息）。
     * 如果条目使用 CUSTOM 加密且未解锁，则敏感字段返回空字符串。
     */
    public VaultEntryFormData loadForm(String id) {
        return jdbi.withHandle(h -> {
            VaultEntryEntity entity = h.attach(VaultEntryDao.class).findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Vault entry not found: " + id));

            boolean canDecrypt = entity.getEncryptionMode() != VaultEncryptionMode.CUSTOM || vaultKeyService.isUnlocked();
            String plainPassword = canDecrypt ? decryptOrNull(entity.getEncryptedPassword(), entity.getEncryptionMode()) : "";
            String plainPassphrase = canDecrypt ? decryptOrNull(entity.getEncryptedPassphrase(), entity.getEncryptionMode()) : "";
            String plainKeyContent = canDecrypt ? decryptOrNull(entity.getEncryptedKeyContent(), entity.getEncryptionMode()) : "";

            return new VaultEntryFormData(
                    entity.getId(),
                    entity.getName(),
                    entity.getAuthenticationType(),
                    entity.getEncryptionMode(),
                    plainPassword,
                    plainPassphrase,
                    plainKeyContent,
                    entity.getPrivateKeyPath(),
                    entity.getProjectId()
            );
        });
    }

    public VaultEntryProfile save(VaultEntryFormData formData) {
        if (formData.name() == null || formData.name().isBlank()) {
            throw new IllegalArgumentException("Vault entry name is required");
        }
        VaultEncryptionMode mode = formData.encryptionMode() != null
                ? formData.encryptionMode() : VaultEncryptionMode.SYSTEM;

        if (mode == VaultEncryptionMode.CUSTOM && !vaultKeyService.isUnlocked()) {
            throw new IllegalStateException("Vault is locked — unlock master password first");
        }

        return jdbi.inTransaction(h -> {
            VaultEntryDao dao = h.attach(VaultEntryDao.class);
            boolean isNew = formData.id() == null || formData.id().isBlank();
            VaultEntryEntity entity = isNew
                    ? new VaultEntryEntity()
                    : dao.findById(formData.id())
                    .orElseThrow(() -> new IllegalArgumentException("Vault entry not found: " + formData.id()));

            entity.setName(formData.name());
            entity.setAuthenticationType(formData.authenticationType());
            entity.setEncryptionMode(mode);
            entity.setEncryptedPassword(encryptOrNull(blankToNull(formData.password()), mode));
            entity.setEncryptedPassphrase(encryptOrNull(blankToNull(formData.passphrase()), mode));
            entity.setEncryptedKeyContent(encryptOrNull(blankToNull(formData.keyContent()), mode));
            entity.setPrivateKeyPath(blankToNull(formData.privateKeyPath()));
            entity.setProjectId(blankToNull(formData.projectId()));

            if (isNew) {
                entity.prepareInsert();
                dao.insert(entity);
            } else {
                entity.prepareUpdate();
                dao.update(entity);
            }

            return toProfile(entity);
        });
    }

    public void delete(String id) {
        jdbi.useHandle(h -> h.attach(VaultEntryDao.class).deleteById(id));
    }

    /**
     * 从凭据库条目构建运行期 CredentialPayload。
     * 如果条目使用 CUSTOM 加密且未解锁，抛出 IllegalStateException。
     */
    public CredentialPayload toCredentialPayload(String vaultEntryId) {
        return jdbi.withHandle(h -> {
            VaultEntryEntity entity = h.attach(VaultEntryDao.class).findById(vaultEntryId)
                    .orElseThrow(() -> new IllegalArgumentException("Vault entry not found: " + vaultEntryId));

            if (entity.getEncryptionMode() == VaultEncryptionMode.CUSTOM && !vaultKeyService.isUnlocked()) {
                throw new IllegalStateException("Vault is locked — master password required to connect");
            }

            if (entity.getAuthenticationType() == AuthenticationType.PASSWORD) {
                String pwd = decryptOrNull(entity.getEncryptedPassword(), entity.getEncryptionMode());
                return CredentialPayload.forPassword(value(pwd).toCharArray());
            }

            // PRIVATE_KEY
            String passphrase = decryptOrNull(entity.getEncryptedPassphrase(), entity.getEncryptionMode());
            String keyContent = decryptOrNull(entity.getEncryptedKeyContent(), entity.getEncryptionMode());

            if (keyContent != null && !keyContent.isBlank()) {
                return CredentialPayload.forPrivateKeyContent(
                        keyContent.getBytes(StandardCharsets.UTF_8),
                        value(passphrase).toCharArray()
                );
            }

            String keyPath = entity.getPrivateKeyPath();
            if (keyPath != null && !keyPath.isBlank()) {
                return CredentialPayload.forPrivateKey(
                        Path.of(keyPath),
                        value(passphrase).toCharArray()
                );
            }

            throw new IllegalStateException("Vault entry has no key content or key path: " + vaultEntryId);
        });
    }

    /**
     * 从文件导入密钥内容，返回预填充的表单数据。
     */
    public VaultEntryFormData importKeyFile(String filePath, String projectId) {
        try {
            Path path = Path.of(filePath);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new VaultEntryFormData(
                    null, path.getFileName().toString(),
                    AuthenticationType.PRIVATE_KEY, VaultEncryptionMode.SYSTEM, "", "",
                    content, filePath, projectId
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to read key file: " + filePath, e);
        }
    }

    public void clearProjectIdForProject(String projectId) {
        jdbi.useHandle(h -> h.attach(VaultEntryDao.class).clearProjectIdForProject(projectId));
    }

    public VaultKeyService getKeyService() {
        return vaultKeyService;
    }

    // ── Dual-mode encrypt/decrypt ─────────────────────────────────────

    private String encryptOrNull(String plainText, VaultEncryptionMode mode) {
        if (plainText == null) return null;
        return mode == VaultEncryptionMode.CUSTOM
                ? vaultKeyService.encryptWithCustomKey(plainText)
                : credentialCipher.encrypt(plainText);
    }

    private String decryptOrNull(String cipherText, VaultEncryptionMode mode) {
        if (cipherText == null) return null;
        return mode == VaultEncryptionMode.CUSTOM
                ? vaultKeyService.decryptWithCustomKey(cipherText)
                : credentialCipher.decrypt(cipherText);
    }

    // ── Mapping ───────────────────────────────────────────────────────

    private VaultEntryProfile toProfile(VaultEntryEntity entity) {
        return new VaultEntryProfile(
                entity.getId(),
                entity.getName(),
                entity.getAuthenticationType(),
                entity.getEncryptionMode(),
                entity.getProjectId(),
                entity.getPrivateKeyPath()
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
