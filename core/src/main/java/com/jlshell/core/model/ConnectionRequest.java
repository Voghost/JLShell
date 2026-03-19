package com.jlshell.core.model;

import com.jlshell.core.security.CredentialPayload;

/**
 * 创建 SSH 会话所需的完整参数。
 * 这里将目标地址、认证方式和 Host Key 校验策略收敛为一个不可变请求对象，
 * 方便 UI、持久化层和连接层之间解耦。
 */
public record ConnectionRequest(
        String displayName,
        ConnectionTarget target,
        AuthenticationMethod authenticationMethod,
        CredentialPayload credential,
        HostKeyVerificationMode hostKeyVerificationMode
) {

    public ConnectionRequest {
        // 当 UI 未显式设置显示名时，提供一个稳定可读的默认值。
        if (displayName == null || displayName.isBlank()) {
            displayName = target.username() + "@" + target.host();
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (authenticationMethod == null) {
            throw new IllegalArgumentException("authenticationMethod must not be null");
        }
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        if (credential.authenticationMethod() != authenticationMethod) {
            throw new IllegalArgumentException("credential authentication method does not match request");
        }
        // 默认使用严格校验，避免桌面 SSH 客户端在无提示下弱化安全性。
        hostKeyVerificationMode = hostKeyVerificationMode == null
                ? HostKeyVerificationMode.STRICT
                : hostKeyVerificationMode;
    }
}
