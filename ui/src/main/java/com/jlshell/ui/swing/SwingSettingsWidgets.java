package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import javafx.scene.Node;
import javafx.scene.control.*;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Native collections for settings. Models are snapshotted on FX; rendering/editing runs on EDT. */
final class SwingSettingsWidgets {
    private SwingSettingsWidgets() {}
    private record Cell(Object item, Supplier<JComponent> graphic) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Supplier<JComponent> list(SwingFormRenderer renderer, ListView source) {
        String id=source.getId();
        boolean pluginList=source.getStyleClass().contains("plugin-manager-list");
        AtomicReference<JList<Cell>> view = new AtomicReference<>();
        AtomicReference<List<Cell>> snapshot = new AtomicReference<>(List.of());
        Runnable refresh = () -> {
            List<Cell> cells = new ArrayList<>();
            for (int index=0; index<source.getItems().size(); index++) {
                Object item=source.getItems().get(index);
                ListCell cell=source.getCellFactory()==null ? new ListCell() : (ListCell)source.getCellFactory().call(source);
                cell.updateListView(source); cell.updateIndex(index);
                Node graphic=cell.getGraphic();
                String text=cell.getText();
                cells.add(new Cell(item, item instanceof com.jlshell.terminal.model.TerminalColorScheme scheme
                        ? () -> schemeCell(scheme, text)
                        : graphic==null ? () -> new JLabel(text==null?String.valueOf(item):text) : renderer.prepare(graphic)));
            }
            snapshot.set(List.copyOf(cells));
            int selected=source.getSelectionModel().getSelectedIndex();
            SwingUtilities.invokeLater(() -> {
                if(renderer.isClosed()||view.get()==null)return;
                JList<Cell> list=view.get();list.putClientProperty("jlshell.updating",true);
                list.setListData(cells.toArray(Cell[]::new));list.setSelectedIndex(selected);
                list.putClientProperty("jlshell.updating",false);
            });
        };
        refresh.run();
        javafx.collections.ListChangeListener listener=change -> refresh.run();
        source.getItems().addListener(listener);renderer.onDispose(() -> source.getItems().removeListener(listener));
        var selected=renderer.observe(source.getSelectionModel().selectedIndexProperty(), next -> {
            if(view.get()!=null){view.get().putClientProperty("jlshell.updating",true);view.get().setSelectedIndex(((Number)next).intValue());view.get().putClientProperty("jlshell.updating",false);}
        });
        return () -> {
            JList<Cell> list=new JList<>(snapshot.get().toArray(Cell[]::new)){
                @Override public boolean getScrollableTracksViewportWidth(){return true;}
            };view.set(list);
            list.setName(id); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var graphics=new java.util.IdentityHashMap<Cell,JComponent>();
            list.setCellRenderer((items,cell,index,isSelected,focus) -> {
                JComponent component=graphics.computeIfAbsent(cell,key->key.graphic().get());
                component.setPreferredSize(null);
                constrainCell(component);
                component.setOpaque(true);
                component.setBackground(isSelected?items.getSelectionBackground():items.getBackground());
                if (cell.item() instanceof com.jlshell.terminal.model.TerminalColorScheme) {
                    JLabel check = (JLabel) component.getClientProperty("scheme.check");
                    check.setText(isSelected ? "✓" : " ");
                    check.setForeground(items.getSelectionForeground());
                    Color accent = UIManager.getColor("Component.focusColor");
                    component.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(isSelected && accent != null ? accent : items.getBackground(), 2, true),
                            BorderFactory.createEmptyBorder(6,8,6,8)));
                } else component.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
                int width=Math.max(120,items.getWidth());
                int height=pluginList?Math.max(68,items.getFontMetrics(items.getFont()).getHeight()*3+22):component.getPreferredSize().height;
                component.setPreferredSize(new Dimension(width,height));component.setSize(width,height);
                layout(component);
                return component;
            });
            list.setSelectedIndex(((Number)selected.get()).intValue());
            SwingUtilities.invokeLater(() -> { if (!renderer.isClosed() && list.getSelectedIndex() >= 0) list.ensureIndexIsVisible(list.getSelectedIndex()); });
            list.addListSelectionListener(event -> {
                if(!event.getValueIsAdjusting()&&!Boolean.TRUE.equals(list.getClientProperty("jlshell.updating"))) {
                    int index=list.getSelectedIndex();FxThread.run(() -> {if(!renderer.isClosed())source.getSelectionModel().select(index);});
                }
            });
            JScrollPane scroll=new JScrollPane(list);scroll.setMinimumSize(new Dimension(160,120));
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){
                public void componentResized(java.awt.event.ComponentEvent event){int width=scroll.getViewport().getExtentSize().width;if(width>0)list.setFixedCellWidth(width);}
            });
            scroll.setPreferredSize(new Dimension(250,230));return scroll;
        };
    }

    private static JComponent schemeCell(com.jlshell.terminal.model.TerminalColorScheme scheme, String text) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        JPanel heading = new JPanel(new BorderLayout()); heading.setOpaque(false);
        JLabel check = new JLabel(" "); check.setPreferredSize(new Dimension(24, 18));
        heading.add(new JLabel(text)); heading.add(check, BorderLayout.EAST);
        card.putClientProperty("scheme.check", check);
        card.add(heading, BorderLayout.NORTH);
        JPanel sample = new JPanel(new BorderLayout(12, 0));
        sample.setBackground(scheme.background());
        sample.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        JLabel prompt = new JLabel("user@server ~ $ ls");
        prompt.setForeground(scheme.foreground());
        prompt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sample.add(prompt, BorderLayout.WEST);
        JPanel swatches = new JPanel(new GridLayout(1, 8, 3, 0)); swatches.setOpaque(false);
        java.awt.Color[] colors = scheme.ansiColors();
        for (int i = 0; i < 8; i++) {
            JPanel swatch = new JPanel(); swatch.setBackground(colors[i]);
            swatch.setPreferredSize(new Dimension(12, 14)); swatches.add(swatch);
        }
        sample.add(swatches, BorderLayout.EAST); card.add(sample);
        return card;
    }

    private record TreeEntry(TreeItem<?> model, Supplier<JComponent> graphic, List<TreeEntry> children, boolean expanded) {}
    private record TreeViewNode(TreeItem<?> model, JComponent graphic) {}

    @SuppressWarnings({"rawtypes","unchecked"})
    static Supplier<JComponent> tree(SwingFormRenderer renderer, TreeView source) {
        boolean showRoot=source.isShowRoot();
        AtomicReference<JTree> view=new AtomicReference<>();
        java.util.function.Function<TreeItem<?>,TreeEntry> snapshot=new java.util.function.Function<>() {
            public TreeEntry apply(TreeItem<?> item) {
                Object value=item.getValue();
                Supplier<JComponent> graphic=value instanceof Node node?renderer.prepare(node):()->new JLabel(String.valueOf(value));
                return new TreeEntry(item,graphic,item.getChildren().stream().map(this).toList(),item.isExpanded());
            }
        };
        var root=renderer.observe(source.rootProperty(), next -> {
            if(view.get()!=null) FxThread.run(() -> {
                TreeEntry entry=next==null?null:snapshot.apply((TreeItem<?>)next);
                SwingUtilities.invokeLater(() -> {if(!renderer.isClosed())installTree(view.get(),entry);});
            });
        });
        TreeEntry initial=source.getRoot()==null?null:snapshot.apply(source.getRoot());
        return () -> {
            JTree tree=new JTree();view.set(tree);tree.setName("settings.actionsTree");tree.setRootVisible(showRoot);
            tree.setRowHeight(34);tree.setShowsRootHandles(true);
            tree.setCellRenderer((t,value,selected,expanded,leaf,row,focus) -> {
                var item=(TreeViewNode)((DefaultMutableTreeNode)value).getUserObject();
                JComponent component=item.graphic();component.setPreferredSize(new Dimension(Math.max(600,t.getWidth()-70),34));component.setSize(component.getPreferredSize());layout(component);
                component.setOpaque(selected);if(selected)component.setBackground(UIManager.getColor("Tree.selectionBackground"));
                return component;
            });
            tree.addComponentListener(new java.awt.event.ComponentAdapter(){
                public void componentResized(java.awt.event.ComponentEvent event){tree.setRowHeight(0);tree.setRowHeight(34);}
            });
            tree.setEditable(true);
            tree.setCellEditor(new NativeTreeEditor());
            tree.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent event) {
                    TreePath path=tree.getPathForLocation(event.getX(),event.getY());if(path==null)return;
                    var item=(TreeViewNode)((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
                    Rectangle bounds=tree.getPathBounds(path);
                    item.graphic().setSize(Math.max(600,tree.getWidth()-bounds.x-20),bounds.height);layout(item.graphic());
                    Component clicked=SwingUtilities.getDeepestComponentAt(item.graphic(),event.getX()-bounds.x,event.getY()-bounds.y);
                    while(clicked!=null&&!(clicked instanceof AbstractButton))clicked=clicked.getParent();
                    if(clicked instanceof AbstractButton button) {
                        tree.startEditingAtPath(path);button.doClick();button.requestFocusInWindow();event.consume();
                    }
                }
            });
            installTree(tree,initial);return new JScrollPane(tree);
        };
    }

    private static void installTree(JTree tree,TreeEntry entry) {
        if(entry==null){tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));return;}
        java.util.function.Function<TreeEntry,DefaultMutableTreeNode> convert=new java.util.function.Function<>() {
            public DefaultMutableTreeNode apply(TreeEntry value) {
                var node=new DefaultMutableTreeNode(new TreeViewNode(value.model(),value.graphic().get()));
                for(TreeEntry child:value.children())node.add(apply(child));return node;
            }
        };
        tree.setModel(new DefaultTreeModel(convert.apply(entry)));
        for(int i=0;i<tree.getRowCount();i++)tree.expandRow(i);
    }

    private static final class NativeTreeEditor extends AbstractCellEditor implements TreeCellEditor {
        public Object getCellEditorValue(){return null;}
        public Component getTreeCellEditorComponent(JTree tree,Object value,boolean selected,boolean expanded,boolean leaf,int row) {
            return ((TreeViewNode)((DefaultMutableTreeNode)value).getUserObject()).graphic();
        }
        public boolean isCellEditable(java.util.EventObject event){return true;}
    }

    private static void constrainCell(Component component){
        if(component instanceof JComponent child&&!(component instanceof AbstractButton)) {
            Dimension minimum=child.getMinimumSize();
            boolean avatar=child.getClientProperty("jlshell.styleClasses") instanceof List<?> styles&&styles.contains("plugin-avatar");
            child.setMinimumSize(new Dimension(avatar?40:0,minimum.height));
        }
        if(component instanceof Container parent)for(Component child:parent.getComponents())constrainCell(child);
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for(Component child:parent.getComponents())if(child instanceof Container nested)layout(nested);
    }
}
