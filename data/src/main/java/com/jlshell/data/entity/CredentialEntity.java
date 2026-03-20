package com.jlshell.data.entity;

/**
 * 凭证实体（纯 POJO，由 JDBI 映射）。
 * 密码和私钥口令在数据库中以 AES 密文存储，读写时由服务层负责加解密。
 */
public class CredentialEntity extends AbstractAuditableEntity {

    private AuthenticationType authenticationType;
    private String encryptedPassword;
    private String encryptedPassphrase;
    private String privateKeyPath;

    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public String getEncryptedPassphrase() { return encryptedPassphrase; }
    public void setEncryptedPassphrase(String encryptedPassphrase) { this.encryptedPassphrase = encryptedPassphrase; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
}
