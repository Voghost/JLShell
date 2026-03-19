package com.jlshell.data.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证加密配置。
 * 默认在用户目录下生成并持久化 AES 主密钥，避免把密钥硬编码进代码或仓库。
 */
@ConfigurationProperties(prefix = "jlshell.data.security")
public class CredentialEncryptionProperties {

    private String masterKeyBase64;
    private Path masterKeyFile = Paths.get(System.getProperty("user.home"), ".jlshell", "master.key");

    public String getMasterKeyBase64() {
        return masterKeyBase64;
    }

    public void setMasterKeyBase64(String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    public Path getMasterKeyFile() {
        return masterKeyFile;
    }

    public void setMasterKeyFile(Path masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }
}
