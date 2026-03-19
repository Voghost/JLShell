package com.jlshell.data.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 AES/GCM 的字符串加密实现。
 * 输出格式为 base64(iv + ciphertext + tag)。
 */
public class AesGcmCredentialCipher implements CredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCredentialCipher(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length);
            payload.put(iv);
            payload.put(ciphertext);
            return Base64.getEncoder().encodeToString(payload.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt credential payload", exception);
        }
    }

    @Override
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return encryptedValue;
        }

        try {
            byte[] payload = Base64.getDecoder().decode(encryptedValue);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt credential payload", exception);
        }
    }
}
