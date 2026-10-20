package com.jlshell.ui.swing;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.terminal.support.SwingExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class WindowsWorkbenchPolishTest {
    @Test void cyclicFolderRecordsRemainVisibleInTheSwingConnectionList() throws Exception {
        var reference = new AtomicReference<SwingWorkbench>();
        var treeRef = new AtomicReference<JTree>(); var searchRef = new AtomicReference<JTextField>();
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
                Runnable noop = () -> {};
                var actions = new SwingWorkbench.Actions(noop,noop,noop,noop,noop,noop,noop,noop,noop,
                        profile -> {}, items -> {}, project -> {}, collapsed -> {}, tab -> {}, (tab,index) -> {},
                        (items,parent) -> {}, (item,reply) -> reply.accept(List.of()), (tab,reply) -> reply.accept(List.of()));
                var view = new SwingWorkbench(actions, Map.of()); reference.set(view);
                var folders = List.of(new FolderProfile("self","Self","self",null,0),
                        new FolderProfile("a","A","b",null,0), new FolderProfile("b","B","a",null,0),
                        new FolderProfile("child","Child","b",null,0), new FolderProfile("orphan","Orphan","missing",null,0));
                var connections = folders.stream().map(folder -> profile("conn-"+folder.id(),folder.id())).toList();
                view.updateConnections(folders,connections,Set.of()); view.show();
                JTree tree=descendants(view.frame()).stream().filter(JTree.class::isInstance).map(JTree.class::cast).findFirst().orElseThrow();
                treeRef.set(tree);
                var root=(DefaultMutableTreeNode)tree.getModel().getRoot();
                assertEquals(11, java.util.Collections.list(root.preorderEnumeration()).size());
                view.focusConnection("conn-child"); assertEquals("conn-child",selectedId(tree));
                JTextField search=descendants(view.frame()).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> "sidebar.search.prompt".equals(field.getClientProperty("JTextField.placeholderText"))).findFirst().orElseThrow();
                searchRef.set(search); search.setText("no match");
            }).get(15,TimeUnit.SECONDS);
            awaitTreeSize(treeRef.get(),1);
            SwingExecutors.runOnEdtAsync(() -> searchRef.get().setText("")).get(5,TimeUnit.SECONDS);
            awaitTreeSize(treeRef.get(),11);
        } finally { if(reference.get()!=null) SwingExecutors.runOnEdtAsync(reference.get()::close).get(10,TimeUnit.SECONDS); }
    }

    private static void awaitTreeSize(JTree tree,int expected) throws Exception {
        for(int attempt=0;attempt<100;attempt++) {
            boolean ready=SwingExecutors.supplyOnEdtAsync(() -> java.util.Collections.list(
                    ((DefaultMutableTreeNode)tree.getModel().getRoot()).preorderEnumeration()).size()==expected).get(5,TimeUnit.SECONDS);
            if(ready) return; Thread.sleep(20);
        }
        fail("Connection search did not update the recovered hierarchy");
    }

    @Test void activeTabFocusUsesExactIdAndHoverDoesNotChangeSelection() throws Exception {
        var reference = new AtomicReference<SwingWorkbench>();
        var active = new AtomicReference<Object>();
        Runnable noop = () -> {};
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> fail("Hover/focus must not connect"), selection -> {}, project -> {}, folders -> {}, active::set,
                        (tab, position) -> {}, (items, folder) -> {}, (item, reply) -> reply.accept(List.of()),
                        (tab, reply) -> reply.accept(List.of()), (key, value) -> {},
                        () -> SwingUtilities.invokeLater(() -> reference.get().focusConnection((String) active.get())));
                SwingWorkbench view = new SwingWorkbench(actions, Map.of()); reference.set(view);
                view.updateConnections(List.of(new FolderProfile("folder", "Collapsed folder", null, null, 0)),
                        List.of(profile("first", null), profile("second", "folder")), Set.of("folder"));
                JPanel first = new JPanel(), second = new JPanel();
                view.addTab("first", "Same name", first, noop); view.addTab("second", "Same name", second, noop);
                view.show(); view.frame().validate();
                JTabbedPane tabs = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, second);
                tabs.setSelectedIndex(0); tabs.setSelectedIndex(1);
                assertEquals("second", active.get());
                view.focusConnection("first");
                JButton focus = descendants(view.frame()).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> "sidebar.focus".equals(button.getToolTipText())).findFirst().orElseThrow();
                focus.doClick(0);
            }).get(15, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                SwingWorkbench view=reference.get();
                JTree tree=descendants(view.frame()).stream().filter(JTree.class::isInstance).map(JTree.class::cast).findFirst().orElseThrow();
                assertEquals("second", selectedId(tree));
                assertTrue(tree.isExpanded(tree.getSelectionPath().getParentPath()));
                TreePath first=path(tree,"first"); Rectangle row=tree.getPathBounds(first);
                tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,tree.getWidth()-12,row.y+row.height/2,0,false));
                BufferedImage image=new BufferedImage(tree.getWidth(),tree.getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();try{tree.paint(g);}finally{g.dispose();}
                assertEquals(SwingTheme.color("treeHover").getRGB(),image.getRGB(tree.getWidth()-12,row.y+row.height/2));
                assertEquals("second",selectedId(tree));
                tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_EXITED,System.currentTimeMillis(),0,-1,-1,0,false));
                g=image.createGraphics();try{tree.paint(g);}finally{g.dispose();}
                assertEquals(tree.getBackground().getRGB(),image.getRGB(tree.getWidth()-12,row.y+row.height/2));
                JTextField search=descendants(view.frame()).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> "sidebar.search.prompt".equals(field.getClientProperty("JTextField.placeholderText"))).findFirst().orElseThrow();
                search.setText("hidden by filter"); view.focusConnection("second");
                assertEquals("",search.getText()); assertEquals("second",selectedId(tree));
            }).get(15, TimeUnit.SECONDS);
        } finally { if(reference.get()!=null) SwingExecutors.runOnEdtAsync(reference.get()::close).get(10,TimeUnit.SECONDS); }
    }

    @Test void splitterRetainsResizeCursorDuringDragAndAfterThemeUpdate() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK,"#3574f0",null,13);
            JFrame frame=new JFrame();
            try {
                ResizeSplitPane split=new ResizeSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JPanel(),new JPanel());
                split.setDividerSize(6);frame.add(split);frame.setSize(500,300);frame.setVisible(true);split.setDividerLocation(200);frame.validate();
                var divider=((BasicSplitPaneUI)split.getUI()).getDivider();
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_ENTERED,0,0,0));
                assertEquals(Cursor.E_RESIZE_CURSOR,frame.getCursor().getType());
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_PRESSED,2,2,MouseEvent.BUTTON1));
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_EXITED,-10,2,0));
                assertEquals(Cursor.E_RESIZE_CURSOR,frame.getCursor().getType());
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_RELEASED,-10,2,MouseEvent.BUTTON1));
                assertEquals(Cursor.DEFAULT_CURSOR,frame.getCursor().getType());
                split.updateUI();divider=((BasicSplitPaneUI)split.getUI()).getDivider();
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_ENTERED,0,0,0));
                assertEquals(Cursor.E_RESIZE_CURSOR,divider.getCursor().getType());
                divider.dispatchEvent(mouse(divider,MouseEvent.MOUSE_EXITED,-10,2,0));
                assertEquals(Cursor.DEFAULT_CURSOR,frame.getCursor().getType());
            } finally {frame.dispose();}
        }).get(15,TimeUnit.SECONDS);
    }
    private static MouseEvent mouse(Component c,int type,int x,int y,int button){return new MouseEvent(c,type,System.currentTimeMillis(),0,x,y,1,false,button);}
    private static ConnectionProfile profile(String id,String folder){return new ConnectionProfile(id,"Same name","192.0.2.1",22,"user",null,null,"","",false,null,ConnectionType.SSH,folder,null);}
    private static String selectedId(JTree tree){return ((SidebarItem)((DefaultMutableTreeNode)tree.getSelectionPath().getLastPathComponent()).getUserObject()).id();}
    private static TreePath path(JTree tree,String id){var nodes=((DefaultMutableTreeNode)tree.getModel().getRoot()).preorderEnumeration();while(nodes.hasMoreElements()){var n=(DefaultMutableTreeNode)nodes.nextElement();if(n.getUserObject() instanceof SidebarItem i&&id.equals(i.id()))return new TreePath(n.getPath());}throw new AssertionError(id);}
    private static List<Component> descendants(Container root){var result=new java.util.ArrayList<Component>();for(Component c:root.getComponents()){result.add(c);if(c instanceof Container child)result.addAll(descendants(child));}return result;}
}
