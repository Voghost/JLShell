package com.jlshell.program.api;

/**
 * 非敏感的宿主账号会话快照。
 *
 * <p>访问令牌始终留在宿主的加密存储中，绝不能通过此类型暴露给插件。</p>
 */
public record AccountSession(
        boolean authenticated,
        String baseUrl,
        String deviceId,
        String accountId,
        String username,
        String email,
        String role,
        String expiresAt
) {
    public AccountSession {
        baseUrl = value(baseUrl);
        deviceId = value(deviceId);
        accountId = value(accountId);
        username = value(username);
        email = value(email);
        role = value(role);
        expiresAt = value(expiresAt);
    }

    public static AccountSession signedOut(String baseUrl, String deviceId) {
        return new AccountSession(false, baseUrl, deviceId, "", "", "", "", "");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
