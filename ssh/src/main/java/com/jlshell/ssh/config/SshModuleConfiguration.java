package com.jlshell.ssh.config;

import java.util.concurrent.ExecutorService;

import com.jlshell.core.service.ConnectionManager;
import com.jlshell.ssh.support.EphemeralTrustHostKeyVerifier;
import com.jlshell.ssh.support.SshjConnectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSH 模块装配。
 * 仅在没有自定义实现时提供默认 SSHJ 适配。
 */
@Configuration
public class SshModuleConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EphemeralTrustHostKeyVerifier ephemeralTrustHostKeyVerifier() {
        return new EphemeralTrustHostKeyVerifier();
    }

    @Bean
    @ConditionalOnMissingBean(ConnectionManager.class)
    public ConnectionManager connectionManager(
            @Qualifier("sshConnectionExecutor") ExecutorService sshConnectionExecutor,
            EphemeralTrustHostKeyVerifier hostKeyVerifier
    ) {
        return new SshjConnectionManager(sshConnectionExecutor, hostKeyVerifier);
    }
}
