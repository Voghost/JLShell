package com.jlshell.core.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.ConnectionManager;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.service.SessionRegistry;
import com.jlshell.core.service.impl.DefaultSessionManager;
import com.jlshell.core.service.impl.InMemorySessionRegistry;
import com.jlshell.core.service.impl.PersistentFontProfileService;
import com.jlshell.core.support.NamedThreadFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core 模块默认装配。
 * 这里仅提供抽象层的默认实现，具体 SSH 行为由 ssh 模块覆盖或补充。
 */
@Configuration
@EnableConfigurationProperties(CoreExecutorProperties.class)
public class CoreModuleConfiguration {

    @Bean(name = "sshConnectionExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "sshConnectionExecutor")
    public ExecutorService sshConnectionExecutor(CoreExecutorProperties properties) {
        // 使用有界队列防止连接风暴压垮桌面应用内存。
        return new ThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAlive().toSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                new NamedThreadFactory("jlshell-ssh"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionRegistry sessionRegistry() {
        return new InMemorySessionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public FontProfileService fontProfileService(AppSettingsService appSettingsService) {
        return new PersistentFontProfileService(appSettingsService);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(ConnectionManager connectionManager, SessionRegistry sessionRegistry) {
        // SessionManager 只负责编排，不直接持有任何底层 SSH 实现。
        return new DefaultSessionManager(connectionManager, sessionRegistry);
    }
}
