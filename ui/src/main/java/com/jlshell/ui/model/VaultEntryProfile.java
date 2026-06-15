package com.jlshell.ui.model;

import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;

/**
 * 凭据库列表项模型（不含敏感信息，仅用于展示）。
 */
public record VaultEntryProfile(
        String id,
        String name,
        AuthenticationType authenticationType,
        VaultEncryptionMode encryptionMode,
        String projectId,
        String privateKeyPath
) {}
