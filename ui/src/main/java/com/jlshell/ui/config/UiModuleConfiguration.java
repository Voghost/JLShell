package com.jlshell.ui.config;

import java.util.concurrent.ExecutorService;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.support.JediTermTerminalViewFactory;
import com.jlshell.ui.service.I18nService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

/**
 * UI 模块基础配置。
 */
@Configuration
public class UiModuleConfiguration {

    private final AppSettingsService appSettings;

    public UiModuleConfiguration(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    @PostConstruct
    public void initLocale() {
        String lang = appSettings.get("ui.language", "en");
        if (lang.contains("_")) {
            String[] parts = lang.split("_");
            Locale.setDefault(new Locale(parts[0], parts[1]));
        } else {
            Locale.setDefault(new Locale(lang));
        }
    }

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    /** 覆盖 terminal 模块的默认工厂，注入 i18n 函数以支持右键菜单多语言。 */
    @Bean
    @Primary
    public TerminalViewFactory terminalViewFactory(
            FontProfileService fontProfileService,
            @Qualifier("sshConnectionExecutor") ExecutorService sshConnectionExecutor,
            I18nService i18nService
    ) {
        return new JediTermTerminalViewFactory(fontProfileService, sshConnectionExecutor, i18nService::get);
    }
}
