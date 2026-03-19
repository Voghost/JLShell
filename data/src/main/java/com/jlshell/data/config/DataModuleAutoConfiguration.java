package com.jlshell.data.config;

import javax.crypto.SecretKey;

import com.jlshell.data.crypto.AesGcmCredentialCipher;
import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.data.crypto.FileSystemMasterKeyProvider;
import com.jlshell.data.crypto.MasterKeyProvider;
import com.jlshell.data.entity.ConnectionEntity;
import com.jlshell.data.jpa.converter.EncryptedStringAttributeConverter;
import com.jlshell.data.repository.ConnectionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * data 模块自动配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(CredentialEncryptionProperties.class)
@EntityScan(basePackageClasses = ConnectionEntity.class)
@EnableJpaRepositories(basePackageClasses = ConnectionRepository.class)
public class DataModuleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MasterKeyProvider masterKeyProvider(CredentialEncryptionProperties properties) {
        return new FileSystemMasterKeyProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CredentialCipher credentialCipher(MasterKeyProvider masterKeyProvider) {
        SecretKey secretKey = masterKeyProvider.loadOrCreate();
        CredentialCipher credentialCipher = new AesGcmCredentialCipher(secretKey);
        EncryptedStringAttributeConverter.configure(credentialCipher);
        return credentialCipher;
    }
}
