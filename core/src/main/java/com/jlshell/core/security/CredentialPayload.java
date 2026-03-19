package com.jlshell.core.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.jlshell.core.model.AuthenticationMethod;

/**
 * 运行期凭证载荷。
 * 这里只负责内存态传递，不负责落库加密；AES 存储由 data 模块后续接入。
 */
public final class CredentialPayload {

    private final AuthenticationMethod authenticationMethod;
    private final char[] secret;
    private final Path privateKeyPath;

    private CredentialPayload(AuthenticationMethod authenticationMethod, char[] secret, Path privateKeyPath) {
        this.authenticationMethod = authenticationMethod;
        this.secret = secret == null ? new char[0] : Arrays.copyOf(secret, secret.length);
        this.privateKeyPath = privateKeyPath;
    }

    public static CredentialPayload forPassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        return new CredentialPayload(AuthenticationMethod.PASSWORD, password, null);
    }

    public static CredentialPayload forPrivateKey(Path privateKeyPath, char[] passphrase) {
        if (privateKeyPath == null || !Files.exists(privateKeyPath)) {
            throw new IllegalArgumentException("privateKeyPath must exist");
        }
        return new CredentialPayload(AuthenticationMethod.PRIVATE_KEY, passphrase, privateKeyPath);
    }

    public AuthenticationMethod authenticationMethod() {
        return authenticationMethod;
    }

    public char[] secret() {
        // 返回副本而不是内部数组，避免调用方意外修改内部状态。
        return Arrays.copyOf(secret, secret.length);
    }

    public Path privateKeyPath() {
        return privateKeyPath;
    }

    public void clear() {
        // 在连接完成或失败后主动覆盖敏感字符，降低明文驻留风险。
        Arrays.fill(secret, '\0');
    }
}
