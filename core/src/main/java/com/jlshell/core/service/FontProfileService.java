package com.jlshell.core.service;

import java.util.List;

import com.jlshell.core.model.FontProfile;

/**
 * 终端字体配置服务。
 */
public interface FontProfileService {

    List<FontProfile> listAvailableProfiles();

    FontProfile activeProfile();

    void updateActiveProfile(FontProfile fontProfile);

    /** Called after a preference has been persisted; closing removes this listener. */
    default AutoCloseable onChanged(java.util.function.Consumer<FontProfile> listener) {
        return () -> {};
    }
}
