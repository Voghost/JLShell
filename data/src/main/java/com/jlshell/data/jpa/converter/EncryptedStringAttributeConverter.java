package com.jlshell.data.jpa.converter;

import com.jlshell.data.crypto.CredentialCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA 字符串加密转换器。
 * Hibernate 创建 Converter 实例时不会自动走 Spring 注入，这里通过启动期静态配置桥接。
 */
@Converter
public class EncryptedStringAttributeConverter implements AttributeConverter<String, String> {

    private static volatile CredentialCipher credentialCipher;

    public static void configure(CredentialCipher credentialCipher) {
        EncryptedStringAttributeConverter.credentialCipher = credentialCipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        return cipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return cipher().decrypt(dbData);
    }

    private CredentialCipher cipher() {
        if (credentialCipher == null) {
            throw new IllegalStateException("CredentialCipher is not configured");
        }
        return credentialCipher;
    }
}
