package com.jlshell.ui.model;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FolderHierarchyTest {
    @Test void preservesRecordsAndBreaksCyclesMissingParentsAndProjectCrossings() {
        var source = List.of(folder("self","self"), folder("a","b"), folder("b","a"),
                folder("child","b"), folder("orphan","missing"),
                new FolderProfile("foreign", "Foreign", "child", "other-project", 9));
        var result = FolderHierarchy.from(source);
        assertEquals(source.size(), result.folders().size());
        verifyForest(result);
        var index = index(result);
        for (String root : List.of("self","orphan","foreign")) assertNull(index.get(root).parentId());
        assertEquals("b", index.get("child").parentId());
        assertEquals("other-project", index.get("foreign").projectId());
        assertEquals(9, index.get("foreign").sortOrder());
        assertEquals("self", source.getFirst().parentId(), "Recovery must not rewrite input or persistence");
    }
    @Test void handlesUnorderedAndExtremelyDeepFoldersWithoutRecursiveTraversal() {
        var source = new ArrayList<FolderProfile>();
        for (int id = 0; id < 5000; id++) source.add(folder("folder-"+id, id==0?null:"folder-"+(id-1)));
        Collections.shuffle(source, new Random(19));
        var result = FolderHierarchy.from(source);
        assertEquals(5000, result.folders().size()); verifyForest(result);
        assertTrue(result.depths().values().stream().allMatch(depth -> depth < FolderHierarchy.MAX_DISPLAY_DEPTH));
        var shallow = FolderHierarchy.from(List.of(folder("child","root"), folder("root",null)));
        assertEquals(1, shallow.depths().get("child"));
    }
    @Test void rejectsSelfDescendantAndMixedDragWhileAllowingValidMoves() {
        var hierarchy = FolderHierarchy.from(List.of(folder("root",null), folder("child","root"), folder("leaf","child"),folder("other",null)));
        var root = new SidebarItem.FolderItem("root","Root",null);
        var other = new SidebarItem.FolderItem("other","Other",null);
        assertFalse(hierarchy.canMove(List.of(root),"root"));
        assertFalse(hierarchy.canMove(List.of(root),"leaf"));
        assertFalse(hierarchy.canMove(List.of(other,root),"child"));
        assertTrue(hierarchy.canMove(List.of(root),"other"));
        assertTrue(hierarchy.canMove(List.of(root),null));
        assertFalse(hierarchy.canMove(List.of(root),"missing"));
    }
    private static FolderProfile folder(String id,String parent) { return new FolderProfile(id,id,parent,null,0); }
    private static Map<String,FolderProfile> index(FolderHierarchy result) {
        var map = new HashMap<String,FolderProfile>(); result.folders().forEach(f -> map.put(f.id(),f)); return map;
    }
    private static void verifyForest(FolderHierarchy result) {
        var map = index(result);
        for (var folder : result.folders()) {
            var seen = new HashSet<String>(); String current = folder.id(); int depth=0;
            while (current != null) { assertTrue(seen.add(current)); current=map.get(current).parentId(); if(current!=null) depth++; }
            assertEquals(depth, result.depths().get(folder.id()));
        }
    }
}
