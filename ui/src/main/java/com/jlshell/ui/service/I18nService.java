package com.jlshell.ui.service;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * UI 国际化服务（基于标准 Java ResourceBundle）。
 */
public class I18nService {

    private final ObjectProperty<Locale> localeProperty = new SimpleObjectProperty<>();
    private ResourceBundle bundle;

    public I18nService(Locale locale) {
        Locale.setDefault(locale);
        localeProperty.set(locale);
        loadBundle(locale);
    }

    public ReadOnlyObjectProperty<Locale> localeProperty() {
        return localeProperty;
    }

    public Locale getLocale() {
        return localeProperty.get();
    }

    public void setLocale(Locale locale) {
        Locale.setDefault(locale);
        localeProperty.set(locale);
        loadBundle(locale);
    }

    private void loadBundle(Locale locale) {
        try {
            // Use ResourceBundle.Control to prevent fallback to system default locale.
            // Without this, on a Chinese system, requesting Locale("en") would still
            // match messages_zh_CN.properties via the default fallback chain.
            ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(
                    ResourceBundle.Control.FORMAT_PROPERTIES);
            bundle = ResourceBundle.getBundle("i18n/messages", locale, control);
        } catch (MissingResourceException e) {
            bundle = ResourceBundle.getBundle("i18n/messages", Locale.ROOT);
        }
    }

    public String get(String key, Object... args) {
        try {
            String pattern = bundle.getString(key);
            return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public String getOrDefault(String key, String fallback) {
        try {
            String value = bundle.getString(key);
            return value.isEmpty() ? fallback : value;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }
}
