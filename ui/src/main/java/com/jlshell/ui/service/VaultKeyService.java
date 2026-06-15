package com.jlshell.ui.service;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.jlshell.core.service.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 凭据库用户密钥服务。
 * 管理用户主密码在内存中的生命周期，通过 PBKDF2 派生 AES-256 密钥，
 * 并使用验证令牌（app_settings）确认密码正确性。
 */
public class VaultKeyService {

    private static final Logger log = LoggerFactory.getLogger(VaultKeyService.class);
    private static final String SETTINGS_KEY_SALT = "vault.custom.salt";
    private static final String SETTINGS_KEY_VERIFY = "vault.custom.verify";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 32;
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String VERIFY_PLAINTEXT = "jlshell-vault-verify";

    private final AppSettingsService appSettings;
    private final SecureRandom secureRandom = new SecureRandom();

    // 用户主密码派生的密钥，仅存在于内存中
    private SecretKey customKey;
    private boolean unlocked = false;

    public VaultKeyService(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    /**
     * 用户是否已设置过主密码（即数据库中有验证令牌）。
     */
    public boolean isCustomKeyConfigured() {
        return appSettings.get(SETTINGS_KEY_SALT).isPresent()
                && appSettings.get(SETTINGS_KEY_VERIFY).isPresent();
    }

    /**
     * 主密码是否已解锁（在内存中可用）。
     */
    public boolean isUnlocked() {
        return unlocked && customKey != null;
    }

    /**
     * 首次设置主密码。生成 salt，派生密钥，存储验证令牌。
     */
    public void setupMasterPassword(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) {
            throw new IllegalArgumentException("Master password must not be empty");
        }
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        SecretKey key = deriveKey(masterPassword, salt);
        try {
            String verifyToken = encryptWithKey(VERIFY_PLAINTEXT, key);
            appSettings.set(SETTINGS_KEY_SALT, Base64.getEncoder().encodeToString(salt));
            appSettings.set(SETTINGS_KEY_VERIFY, verifyToken);
            this.customKey = key;
            this.unlocked = true;
            log.info("Vault master password configured");
        } finally {
            if (this.customKey != key) {
                zeroKey(key);
            }
        }
    }

    /**
     * 使用主密码解锁。验证令牌确认密码正确后，密钥保留在内存中。
     * @return true 如果解锁成功
     */
    public boolean unlock(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) return false;
        String saltB64 = appSettings.get(SETTINGS_KEY_SALT).orElse(null);
        String verifyToken = appSettings.get(SETTINGS_KEY_VERIFY).orElse(null);
        if (saltB64 == null || verifyToken == null) return false;

        byte[] salt = Base64.getDecoder().decode(saltB64);
        SecretKey key = deriveKey(masterPassword, salt);
        try {
            String decrypted = decryptWithKey(verifyToken, key);
            if (VERIFY_PLAINTEXT.equals(decrypted)) {
                this.customKey = key;
                this.unlocked = true;
                log.info("Vault unlocked with master password");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (!unlocked) {
                zeroKey(key);
            }
        }
    }

    /**
     * 锁定（清除内存中的密钥）。
     */
    public void lock() {
        zeroKey(customKey);
        this.customKey = null;
        this.unlocked = false;
        log.info("Vault locked");
    }

    /**
     * 更改主密码。需要先解锁。
     */
    public void changeMasterPassword(char[] newMasterPassword) {
        if (!isUnlocked()) {
            throw new IllegalStateException("Vault must be unlocked to change master password");
        }
        setupMasterPassword(newMasterPassword);
    }

    /**
     * 使用用户主密码派生的密钥加密。
     */
    public String encryptWithCustomKey(String plaintext) {
        if (!isUnlocked()) {
            throw new IllegalStateException("Vault is locked — master password required");
        }
        return encryptWithKey(plaintext, customKey);
    }

    /**
     * 使用用户主密码派生的密钥解密。
     */
    public String decryptWithCustomKey(String ciphertext) {
        if (!isUnlocked()) {
            throw new IllegalStateException("Vault is locked — master password required");
        }
        return decryptWithKey(ciphertext, customKey);
    }

    // ── Key derivation ────────────────────────────────────────────────

    private SecretKey deriveKey(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to derive vault key", e);
        } finally {
            spec.clearPassword();
        }
    }

    // ── AES-GCM encrypt/decrypt (same format as AesGcmCredentialCipher) ──

    private String encryptWithKey(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt with custom key", e);
        }
    }

    private String decryptWithKey(String encrypted, SecretKey key) {
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt with custom key", e);
        }
    }

    private void zeroKey(SecretKey key) {
        if (key instanceof SecretKeySpec sks) {
            byte[] encoded = sks.getEncoded();
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }
}
