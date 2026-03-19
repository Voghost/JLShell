package com.jlshell.core.service.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.FontProfileService;

/**
 * 内存版字体服务。
 * 当前用于跑通桌面应用，后续可替换为数据库或用户配置文件实现。
 */
public class InMemoryFontProfileService implements FontProfileService {

    private final CopyOnWriteArrayList<FontProfile> profiles = new CopyOnWriteArrayList<>(List.of(
            new FontProfile("JetBrains Mono", 13, true, 1.0),
            new FontProfile("Cascadia Code", 13, true, 1.0),
            new FontProfile("Monospaced", 13, false, 1.0)
    ));
    private final AtomicReference<FontProfile> activeProfile = new AtomicReference<>(profiles.getFirst());

    @Override
    public List<FontProfile> listAvailableProfiles() {
        return List.copyOf(profiles);
    }

    @Override
    public FontProfile activeProfile() {
        return activeProfile.get();
    }

    @Override
    public void updateActiveProfile(FontProfile fontProfile) {
        profiles.addIfAbsent(fontProfile);
        activeProfile.set(fontProfile);
    }
}
