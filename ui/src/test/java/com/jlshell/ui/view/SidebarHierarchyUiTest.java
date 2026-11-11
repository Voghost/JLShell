package com.jlshell.ui.view;

import com.jlshell.ui.support.UiThread;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.service.I18nService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class SidebarHierarchyUiTest {
    @BeforeAll static void start() throws Exception {
    }
    @Test void loadsCyclesWithoutLosingConnectionsAndSupportsFilteringAndReload() throws Exception {
        com.jlshell.terminal.support.SwingExecutors.runOnEdtAsync(() -> {
            var view = WorkbenchUiSmokeTest.workbench();
            try {
                var folders = List.of(folder("self", "self"), folder("a", "b"), folder("b", "a"), folder("child", "b"), folder("orphan", "missing"));
                var connections = folders.stream().map(folder -> new ConnectionProfile("conn-"+folder.id(), "Connection "+folder.id(),
                        "example.invalid", 22, "test", null, null, null, null, false, null, ConnectionType.SSH, folder.id(), null)).toList();
                view.updateConnections(folders, connections, Set.of());
                var tree = WorkbenchUiSmokeTest.field(view, "tree", javax.swing.JTree.class);
                assertEquals(10, tree.getRowCount());
                var search = WorkbenchUiSmokeTest.field(view, "search", javax.swing.JTextField.class);
                search.setText("Connection child"); assertTrue(tree.getRowCount() > 0);
                search.setText(""); assertEquals(10, tree.getRowCount());
                view.updateConnections(folders, connections, Set.of("self"));
                assertEquals(10, count((javax.swing.tree.DefaultMutableTreeNode)tree.getModel().getRoot()));
            } finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }
    private static int count(javax.swing.tree.DefaultMutableTreeNode root) { return java.util.Collections.list(root.preorderEnumeration()).size() - 1; }
    @Test void contextMenusTargetFavoriteAndImportFolder() throws Exception {
        var done = new CompletableFuture<Void>();
        com.jlshell.ui.support.UiThread.run(() -> {
            try {
                var i18n = new I18nService(Locale.SIMPLIFIED_CHINESE);
                var view = new SidebarTreeView(i18n, 4);
                var folder = new FolderProfile("folder", "folder", null, null, 0);
                var profile = new ConnectionProfile("server", "server", "example.invalid", 22, "test",
                        null, null, null, null, false, null, ConnectionType.SSH, "folder", null);
                view.populate(List.of(folder), List.of(profile), Set.of());
                var changed = new java.util.concurrent.atomic.AtomicReference<String>();
                view.setOnFavorite((id, favorite) -> changed.set(id + ":" + favorite));
                var conn = new SidebarItem.ConnectionItem("server", "server", ConnectionType.SSH, "test");
                var menu = view.contextMenuFor(conn);
                menu.stream().filter(item -> i18n.get("connection.favorite.add").equals(item.text()))
                        .findFirst().orElseThrow().action().run();
                assertEquals("server:true", changed.get());
                view.setOnImportShare(changed::set);
                view.contextMenuFor(new SidebarItem.FolderItem("folder", "folder", null)).stream()
                        .filter(item -> i18n.get("connection.share.import").equals(item.text()))
                        .findFirst().orElseThrow().action().run();
                assertEquals("folder", changed.get());
                var starred = new ConnectionProfile("server", "server", "example.invalid", 22, "test",
                        null, null, null, null, true, null, ConnectionType.SSH, "folder", null);
                view.populate(List.of(folder), List.of(starred), Set.of());
                view.contextMenuFor(conn).stream()
                        .filter(item -> i18n.get("connection.favorite.remove").equals(item.text()))
                        .findFirst().orElseThrow().action().run();
                assertEquals("server:false", changed.get());
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15, TimeUnit.SECONDS);
    }
    @Test void extremelyDeepSavedFoldersAreSplitIntoSafeDisplayBranches() throws Exception {
        var done = new CompletableFuture<Void>();
        com.jlshell.ui.support.UiThread.run(() -> {
            try {
                var folders = new ArrayList<FolderProfile>();
                for(int id=0;id<2000;id++) folders.add(folder("folder-"+id,id==0?null:"folder-"+(id-1)));
                Collections.reverse(folders);
                var hierarchy = com.jlshell.ui.model.FolderHierarchy.from(folders);
                assertEquals(2000, hierarchy.folders().size());
                assertTrue(hierarchy.folders().stream().filter(folder -> folder.parentId() == null).count() > 1);
                assertTrue(hierarchy.depths().values().stream().allMatch(depth -> depth < com.jlshell.ui.model.FolderHierarchy.MAX_DISPLAY_DEPTH));
                done.complete(null);
            } catch(Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15,TimeUnit.SECONDS);
    }
    private static FolderProfile folder(String id, String parent) { return new FolderProfile(id, id, parent, null, 0); }
}
