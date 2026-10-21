package com.jlshell.ui.view;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.service.I18nService;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class SidebarHierarchyUiTest {
    @BeforeAll static void start() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }
    @Test void loadsCyclesWithoutLosingConnectionsAndSupportsFilteringAndReload() throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                var view = new SidebarTreeView(new I18nService(Locale.SIMPLIFIED_CHINESE), 4);
                var folders = List.of(folder("self", "self"), folder("a", "b"), folder("b", "a"), folder("child", "b"), folder("orphan", "missing"));
                var connections = folders.stream().map(folder -> new ConnectionProfile("conn-"+folder.id(), "Connection "+folder.id(),
                        "example.invalid", 22, "test", null, null, null, null, false, null, ConnectionType.SSH, folder.id(), null)).toList();
                view.populate(folders, connections, Set.of());
                assertEquals(10, collect(view.getTreeView().getRoot()).size());
                view.applyFilter("Connection child");
                assertTrue(collect(view.getTreeView().getRoot()).contains("conn-child"));
                view.applyFilter(""); view.collapseAllFolders(); view.expandAllFolders();
                assertEquals(10, collect(view.getTreeView().getRoot()).size());
                view.populate(folders, connections, Set.of("self"));
                assertEquals(10, collect(view.getTreeView().getRoot()).size());
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15, TimeUnit.SECONDS);
    }
    @Test void extremelyDeepSavedFoldersAreSplitIntoSafeDisplayBranches() throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                var view = new SidebarTreeView(new I18nService(Locale.SIMPLIFIED_CHINESE),4);
                var folders = new ArrayList<FolderProfile>();
                for(int id=0;id<2000;id++) folders.add(folder("folder-"+id,id==0?null:"folder-"+(id-1)));
                Collections.reverse(folders);
                view.populate(folders,List.of(),Set.of());
                assertEquals(2000,collect(view.getTreeView().getRoot()).size());
                assertTrue(view.getTreeView().getRoot().getChildren().size()>1);
                view.applyFilter("folder"); view.applyFilter("");
                assertEquals(2000,collect(view.getTreeView().getRoot()).size());
                done.complete(null);
            } catch(Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15,TimeUnit.SECONDS);
    }
    private static FolderProfile folder(String id, String parent) { return new FolderProfile(id, id, parent, null, 0); }
    private static Set<String> collect(TreeItem<SidebarItem> root) {
        var ids = new HashSet<String>(); var nodes = new ArrayDeque<TreeItem<SidebarItem>>(); nodes.add(root);
        var visited = Collections.newSetFromMap(new IdentityHashMap<TreeItem<SidebarItem>, Boolean>());
        while (!nodes.isEmpty()) {
            var node = nodes.remove(); assertTrue(visited.add(node), "Tree node must appear exactly once");
            if (node.getValue()!=null) assertTrue(ids.add(node.getValue().id()));
            for (var child : node.getChildren()) { assertSame(node, child.getParent()); nodes.add(child); }
        }
        return ids;
    }
}
