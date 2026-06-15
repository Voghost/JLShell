package com.jlshell.data.entity;

/**
 * 凭据库加密模式。
 * SYSTEM — 使用系统自动生成的 master.key 加密（默认）
 * CUSTOM — 使用用户主密码派生的 AES 密钥加密
 */
public enum VaultEncryptionMode {
    SYSTEM,
    CUSTOM
}
