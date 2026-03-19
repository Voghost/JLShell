package com.jlshell.core.service.impl;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;

/**
 * 基于 AppSettingsService 的字体配置服务。
 * 字体配置持久化到 SQLite，重启后自动恢复。
 *
 * <p>使用的 key：
 * <ul>
 *   <li>{@code terminal.font.family}</li>
 *   <li>{@code terminal.font.size}</li>
 *   <li>{@code terminal.font.lineSpacing}</li>
 *   <li>{@code terminal.font.ligatures}</li>
 * </ul>
 */
public class PersistentFontProfileService implements FontProfileService {

    private static final String KEY_FAMILY      = "terminal.font.family";
    private static final String KEY_SIZE        = "terminal.font.size";
    private static final String KEY_SPACING     = "terminal.font.lineSpacing";
    private static final String KEY_LIGATURES   = "terminal.font.ligatures";

    private static final FontProfile DEFAULT = new FontProfile("JetBrains Mono", 13, true, 1.0);

    private final AppSettingsService settings;
    private final AtomicReference<FontProfile> cached;

    public PersistentFontProfileService(AppSettingsService settings) {
        this.settings = settings;
        this.cached = new AtomicReference<>(load());
    }

    @Override
    public List<FontProfile> listAvailableProfiles() {
        return List.of(activeProfile());
    }

    @Override
    public FontProfile activeProfile() {
        return cached.get();
    }

    @Override
    public void updateActiveProfile(FontProfile profile) {
        settings.set(KEY_FAMILY,    profile.family());
        settings.set(KEY_SIZE,      String.valueOf(profile.size()));
        settings.set(KEY_SPACING,   String.valueOf(profile.lineSpacing()));
        settings.set(KEY_LIGATURES, String.valueOf(profile.ligaturesEnabled()));
        cached.set(profile);
    }

    private FontProfile load() {
        String family   = settings.get(KEY_FAMILY,    DEFAULT.family());
        double size     = parseDouble(settings.get(KEY_SIZE,    null), DEFAULT.size());
        double spacing  = parseDouble(settings.get(KEY_SPACING, null), DEFAULT.lineSpacing());
        boolean ligatures = Boolean.parseBoolean(settings.get(KEY_LIGATURES, String.valueOf(DEFAULT.ligaturesEnabled())));
        return new FontProfile(family, size, ligatures, spacing);
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; }
    }
}
