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
    private final byte[] privateKeyContent;

    private CredentialPayload(AuthenticationMethod authenticationMethod, char[] secret,
                              Path privateKeyPath, byte[] privateKeyContent) {
        this.authenticationMethod = authenticationMethod;
        this.secret = secret == null ? new char[0] : Arrays.copyOf(secret, secret.length);
        this.privateKeyPath = privateKeyPath;
        this.privateKeyContent = privateKeyContent == null ? null : Arrays.copyOf(privateKeyContent, privateKeyContent.length);
    }

    public static CredentialPayload forPassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        return new CredentialPayload(AuthenticationMethod.PASSWORD, password, null, null);
    }

    public static CredentialPayload forPrivateKey(Path privateKeyPath, char[] passphrase) {
        if (privateKeyPath == null || !Files.exists(privateKeyPath)) {
            throw new IllegalArgumentException("privateKeyPath must exist");
        }
        return new CredentialPayload(AuthenticationMethod.PRIVATE_KEY, passphrase, privateKeyPath, null);
    }

    public static CredentialPayload forPrivateKeyContent(byte[] keyContent, char[] passphrase) {
        if (keyContent == null || keyContent.length == 0) {
            throw new IllegalArgumentException("keyContent must not be empty");
        }
        return new CredentialPayload(AuthenticationMethod.PRIVATE_KEY, passphrase, null, keyContent);
    }

    public AuthenticationMethod authenticationMethod() {
        return authenticationMethod;
    }

    public char[] secret() {
        return Arrays.copyOf(secret, secret.length);
    }

    public Path privateKeyPath() {
        return privateKeyPath;
    }

    public byte[] privateKeyContent() {
        return privateKeyContent == null ? null : Arrays.copyOf(privateKeyContent, privateKeyContent.length);
    }

    public void clear() {
        Arrays.fill(secret, '\0');
        if (privateKeyContent != null) {
            Arrays.fill(privateKeyContent, (byte) 0);
        }
    }
}
