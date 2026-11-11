package com.jlshell.ui.service;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.beans.PropertyChangeSupport;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UI 国际化服务（基于标准 Java ResourceBundle）。
 */
public class I18nService {

    private record Translation(Locale locale, ResourceBundle bundle) { }

    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private volatile Translation translation;

    public I18nService(Locale locale) {
        Locale.setDefault(Objects.requireNonNull(locale, "locale"));
        translation = new Translation(locale, loadBundle(locale));
    }

    /** Callbacks run on the calling thread after the new translations are available. */
    public Registration onLocaleChanged(Consumer<Locale> listener) {
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean closed = new AtomicBoolean();
        java.beans.PropertyChangeListener adapter = event -> {
            if (closed.get()) return;
            try {
                listener.accept((Locale) event.getNewValue());
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(I18nService.class).warn("Locale listener failed", error);
            }
        };
        changes.addPropertyChangeListener("locale", adapter);
        return () -> {
            closed.set(true);
            changes.removePropertyChangeListener("locale", adapter);
        };
    }

    public Locale getLocale() {
        return translation.locale();
    }

    public synchronized void setLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Locale previous = translation.locale();
        if (previous.equals(locale)) return;
        ResourceBundle bundle = loadBundle(locale);
        Locale.setDefault(locale);
        translation = new Translation(locale, bundle);
        changes.firePropertyChange("locale", previous, locale);
    }

    private ResourceBundle loadBundle(Locale locale) {
        try {
            ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(
                    ResourceBundle.Control.FORMAT_PROPERTIES);
            return ResourceBundle.getBundle("i18n/messages", locale, control);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("i18n/messages", Locale.ROOT);
        }
    }

    public String get(String key, Object... args) {
        try {
            String pattern = translation.bundle().getString(key);
            return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public String getOrDefault(String key, String fallback) {
        try {
            String value = translation.bundle().getString(key);
            return value.isEmpty() ? fallback : value;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    /** 设置搜索索引只读取文本，无需创建对应页面和控件。 */
    public String searchText(String... prefixes) {
        ResourceBundle bundle = translation.bundle();
        return bundle.keySet().stream()
                .filter(key -> java.util.Arrays.stream(prefixes).anyMatch(key::startsWith))
                .map(bundle::getString)
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
