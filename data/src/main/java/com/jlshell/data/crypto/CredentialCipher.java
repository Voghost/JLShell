package com.jlshell.data.crypto;

/**
 * 持久层凭证加密抽象。
 */
public interface CredentialCipher {

    String encrypt(String plaintext);

    String decrypt(String encryptedValue);
}
