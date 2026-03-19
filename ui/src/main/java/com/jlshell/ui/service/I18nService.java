package com.jlshell.ui.service;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * UI 国际化服务。
 */
@Service
public class I18nService {

    private final MessageSource messageSource;

    public I18nService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, Locale.getDefault());
    }
}
