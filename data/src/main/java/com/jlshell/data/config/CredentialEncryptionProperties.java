package com.jlshell.data.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 凭证加密配置（纯 POJO，默认值可通过构造方法覆盖）。
 */
public class CredentialEncryptionProperties {

    private String masterKeyBase64;
    private Path masterKeyFile = Paths.get(System.getProperty("user.home"), ".jlshell", "master.key");

    public CredentialEncryptionProperties() {}

    public CredentialEncryptionProperties(Path masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }

    public String getMasterKeyBase64() { return masterKeyBase64; }
    public void setMasterKeyBase64(String masterKeyBase64) { this.masterKeyBase64 = masterKeyBase64; }

    public Path getMasterKeyFile() { return masterKeyFile; }
    public void setMasterKeyFile(Path masterKeyFile) { this.masterKeyFile = masterKeyFile; }
}
