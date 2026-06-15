package com.jlshell.data.entity;

/**
 * 凭据库实体（纯 POJO，由 JDBI 映射）。
 * 密码、口令和密钥内容在数据库中以 AES 密文存储，读写时由服务层负责加解密。
 */
public class VaultEntryEntity extends AbstractAuditableEntity {

    private String name;
    private AuthenticationType authenticationType;
    private VaultEncryptionMode encryptionMode = VaultEncryptionMode.SYSTEM;
    private String encryptedPassword;
    private String encryptedPassphrase;
    private String encryptedKeyContent;
    private String privateKeyPath;
    private String projectId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }

    public VaultEncryptionMode getEncryptionMode() { return encryptionMode; }
    public void setEncryptionMode(VaultEncryptionMode encryptionMode) { this.encryptionMode = encryptionMode; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public String getEncryptedPassphrase() { return encryptedPassphrase; }
    public void setEncryptedPassphrase(String encryptedPassphrase) { this.encryptedPassphrase = encryptedPassphrase; }

    public String getEncryptedKeyContent() { return encryptedKeyContent; }
    public void setEncryptedKeyContent(String encryptedKeyContent) { this.encryptedKeyContent = encryptedKeyContent; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
}
