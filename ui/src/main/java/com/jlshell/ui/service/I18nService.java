package com.jlshell.ui.service;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * UI 国际化服务（基于标准 Java ResourceBundle）。
 */
public class I18nService {

    private ResourceBundle bundle;

    public I18nService(Locale locale) {
        loadBundle(locale);
    }

    private void loadBundle(Locale locale) {
        try {
            bundle = ResourceBundle.getBundle("i18n/messages", locale);
        } catch (MissingResourceException e) {
            bundle = ResourceBundle.getBundle("i18n/messages", Locale.ENGLISH);
        }
    }

    public void setLocale(Locale locale) {
        Locale.setDefault(locale);
        loadBundle(locale);
    }

    public String get(String key, Object... args) {
        try {
            String pattern = bundle.getString(key);
            return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
