package com.jlshell.ui.model;

import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;

/**
 * 凭据库表单数据（包含解密后的敏感信息，仅用于编辑）。
 */
public record VaultEntryFormData(
        String id,
        String name,
        AuthenticationType authenticationType,
        VaultEncryptionMode encryptionMode,
        String password,
        String passphrase,
        String keyContent,
        String privateKeyPath,
        String projectId
) {
    public static VaultEntryFormData empty(String projectId) {
        return new VaultEntryFormData(
                null, "", AuthenticationType.PASSWORD, VaultEncryptionMode.SYSTEM, "", "", "", "", projectId
        );
    }
}
