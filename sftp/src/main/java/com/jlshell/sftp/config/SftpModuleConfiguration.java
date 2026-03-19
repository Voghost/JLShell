package com.jlshell.sftp.config;

import java.util.concurrent.ExecutorService;

import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.support.SshjSftpService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * sftp 模块装配。
 */
@Configuration
public class SftpModuleConfiguration {

    @Bean
    @ConditionalOnMissingBean(SftpService.class)
    public SftpService sftpService(@Qualifier("sshConnectionExecutor") ExecutorService sshConnectionExecutor) {
        return new SshjSftpService(sshConnectionExecutor);
    }
}
