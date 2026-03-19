package com.jlshell.data.entity;

import com.jlshell.data.jpa.converter.EncryptedStringAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 凭证实体。
 * 密码和私钥口令统一按 AES 加密后的密文存储。
 */
@Entity
@Table(name = "credentials")
public class CredentialEntity extends AbstractAuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_type", nullable = false, length = 32)
    private AuthenticationType authenticationType;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(name = "encrypted_password", length = 4096)
    private String encryptedPassword;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(name = "encrypted_passphrase", length = 4096)
    private String encryptedPassphrase;

    @Column(name = "private_key_path", length = 1024)
    private String privateKeyPath;

    @OneToOne(mappedBy = "credential", fetch = FetchType.LAZY)
    private ConnectionEntity connection;

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(AuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    public String getEncryptedPassphrase() {
        return encryptedPassphrase;
    }

    public void setEncryptedPassphrase(String encryptedPassphrase) {
        this.encryptedPassphrase = encryptedPassphrase;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public ConnectionEntity getConnection() {
        return connection;
    }

    public void setConnection(ConnectionEntity connection) {
        this.connection = connection;
    }
}
