package com.jlshell.ui.model;

import java.util.*;
import org.slf4j.LoggerFactory;

/** A display-only forest: preserve records while isolating invalid parent links before creating UI nodes. */
public final class FolderHierarchy {
    public static final int MAX_DISPLAY_DEPTH = 64;
    private final List<FolderProfile> folders;
    private final Map<String, FolderProfile> byId;
    private final Map<String, Integer> depths;

    private FolderHierarchy(List<FolderProfile> folders, Map<String, Integer> depths) {
        this.folders = List.copyOf(folders);
        var index = new HashMap<String, FolderProfile>();
        folders.forEach(folder -> index.put(folder.id(), folder));
        byId = Map.copyOf(index); this.depths = Map.copyOf(depths);
    }

    public static FolderHierarchy from(List<FolderProfile> source) {
        var original = new LinkedHashMap<String, FolderProfile>();
        source.forEach(folder -> original.putIfAbsent(folder.id(), folder));
        var parents = new HashMap<String, String>();
        original.forEach((id, folder) -> {
            var parent = original.get(folder.parentId());
            parents.put(id, parent != null && !id.equals(parent.id())
                    && Objects.equals(folder.projectId(), parent.projectId()) ? parent.id() : null);
        });
        var depths = new HashMap<String, Integer>();
        for (String id : original.keySet()) {
            if (depths.containsKey(id)) continue;
            var path = new ArrayList<String>(); var seen = new HashSet<String>();
            String current = id;
            while (current != null && !depths.containsKey(current)) {
                if (!seen.add(current)) {
                    parents.put(current, null); depths.put(current, 0); break;
                }
                path.add(current); current = parents.get(current);
            }
            for (int index = path.size()-1; index >= 0; index--) {
                String node = path.get(index), parent = parents.get(node);
                int depth = parent == null ? 0 : depths.get(parent) + 1;
                if (depth >= MAX_DISPLAY_DEPTH) { parents.put(node, null); depth = 0; }
                depths.put(node, depth);
            }
        }
        var safe = new ArrayList<FolderProfile>(); int adjusted = 0;
        for (var folder : original.values()) {
            String parent = parents.get(folder.id());
            if (!Objects.equals(parent, folder.parentId())) adjusted++;
            safe.add(new FolderProfile(folder.id(), folder.name(), parent, folder.projectId(), folder.sortOrder()));
        }
        if (adjusted > 0) LoggerFactory.getLogger(FolderHierarchy.class)
                .warn("Recovered connection folder hierarchy: folders={} adjustedParents={}", safe.size(), adjusted);
        return new FolderHierarchy(safe, depths);
    }

    public List<FolderProfile> folders() { return folders; }
    public Map<String, Integer> depths() { return depths; }

    /** Reject an entire drag if its destination is one of the moved folders or their descendants. */
    public boolean canMove(List<SidebarItem> items, String targetId) {
        if (items.isEmpty() || targetId != null && !byId.containsKey(targetId)) return false;
        var movedFolders = new HashSet<String>();
        for (var item : items) if (item instanceof SidebarItem.FolderItem) {
            if (!byId.containsKey(item.id())) return false;
            movedFolders.add(item.id());
        }
        for (String current = targetId; current != null; current = byId.get(current).parentId()) {
            if (movedFolders.contains(current)) return false;
        }
        return true;
    }
}
