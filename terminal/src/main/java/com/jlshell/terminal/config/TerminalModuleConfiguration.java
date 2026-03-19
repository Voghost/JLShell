package com.jlshell.terminal.config;

import java.util.concurrent.ExecutorService;

import com.jlshell.core.service.FontProfileService;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.support.JediTermTerminalViewFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * terminal 模块默认装配。
 */
@Configuration
public class TerminalModuleConfiguration {

    @Bean
    @ConditionalOnMissingBean(TerminalViewFactory.class)
    public TerminalViewFactory terminalViewFactory(
            FontProfileService fontProfileService,
            @Qualifier("sshConnectionExecutor") ExecutorService sshConnectionExecutor
    ) {
        return new JediTermTerminalViewFactory(fontProfileService, sshConnectionExecutor, null);
    }
}
