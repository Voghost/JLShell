package com.jlshell.ui.service;

import com.jlshell.core.service.AppSettingsService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SidebarExpansionStateStoreTest {

    private final InMemorySettings settings = new InMemorySettings();
    private final SidebarExpansionStateStore store = new SidebarExpansionStateStore(settings);

    @Test
    void keepsExpansionStateIsolatedByProject() {
        store.saveCollapsedFolderIds(null, Set.of("default-folder"));
        store.saveCollapsedFolderIds("project-a", Set.of("folder-a", "folder-b"));

        assertEquals(Set.of("default-folder"), store.loadCollapsedFolderIds(null));
        assertEquals(Set.of("folder-a", "folder-b"), store.loadCollapsedFolderIds("project-a"));
        assertEquals(Set.of(), store.loadCollapsedFolderIds("project-b"));
    }

    @Test
    void roundTripsFolderIdsWithoutDependingOnDelimiters() {
        Set<String> ids = Set.of("folder.with.dot", "目录/一", "line\nbreak");

        store.saveCollapsedFolderIds("project.with.dot", ids);

        assertEquals(ids, store.loadCollapsedFolderIds("project.with.dot"));
    }

    @Test
    void emptyStateMeansEveryFolderIsExpanded() {
        store.saveCollapsedFolderIds("project-a", Set.of());

        assertEquals(Set.of(), store.loadCollapsedFolderIds("project-a"));
    }

    private static final class InMemorySettings implements AppSettingsService {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
