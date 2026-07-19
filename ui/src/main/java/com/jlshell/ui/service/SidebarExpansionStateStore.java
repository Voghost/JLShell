package com.jlshell.ui.service;

import com.jlshell.core.service.AppSettingsService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** 按项目持久化连接树中被用户收起的文件夹。 */
public final class SidebarExpansionStateStore {

    private static final String KEY_PREFIX = "ui.sidebar.collapsedFolders.";

    private final AppSettingsService appSettings;

    public SidebarExpansionStateStore(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    public Set<String> loadCollapsedFolderIds(String projectId) {
        return decode(appSettings.get(settingsKey(projectId), ""));
    }

    public void saveCollapsedFolderIds(String projectId, Set<String> folderIds) {
        appSettings.set(settingsKey(projectId), encode(folderIds));
    }

    private static String settingsKey(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return KEY_PREFIX + "default";
        }
        return KEY_PREFIX + "project." + encodeValue(projectId);
    }

    private static String encode(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SidebarExpansionStateStore::encodeValue)
                .sorted()
                .collect(Collectors.joining("."));
    }

    private static Set<String> decode(String encoded) {
        Set<String> values = new HashSet<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String part : encoded.split("\\.")) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
                if (!value.isBlank()) {
                    values.add(value);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore a damaged entry while retaining the rest of the saved state.
            }
        }
        return values;
    }

    private static String encodeValue(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
