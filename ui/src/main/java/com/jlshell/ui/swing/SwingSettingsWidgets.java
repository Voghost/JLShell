package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.dialog.PreferencesDialog.ManualRow;
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
                cells.add(new Cell(item, graphic==null ? () -> new JLabel(text==null?String.valueOf(item):text) : renderer.prepare(graphic)));
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
                component.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
                int width=Math.max(120,items.getWidth());
                int height=pluginList?Math.max(68,items.getFontMetrics(items.getFont()).getHeight()*3+22):component.getPreferredSize().height;
                component.setPreferredSize(new Dimension(width,height));component.setSize(width,height);
                layout(component);
                return component;
            });
            list.setSelectedIndex(((Number)selected.get()).intValue());
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

    private record TableSnapshot(List<?> rows,List<List<Object>> cells,List<List<String>> secrets) {}
    @SuppressWarnings({"rawtypes","unchecked"})
    static Supplier<JComponent> table(SwingFormRenderer renderer,TableView source) {
        List<TableColumn> columns=List.copyOf(source.getColumns());
        List<String> names=columns.stream().map(TableColumn::getText).toList();
        List<Integer> widths=columns.stream().map(column->(int)column.getPrefWidth()).toList();
        boolean editable=source.isEditable();
        AtomicReference<JTable> view=new AtomicReference<>();
        AtomicReference<TableSnapshot> snapshot=new AtomicReference<>();
        Runnable refresh=()->{
            List<?> rows=List.copyOf(source.getItems());List<List<Object>> values=new ArrayList<>();List<List<String>> secrets=new ArrayList<>();
            for(int row=0;row<rows.size();row++) {
                List<Object> cells=new ArrayList<>();
                for(TableColumn column:columns)cells.add(column.getCellObservableValue(row)==null?null:column.getCellObservableValue(row).getValue());
                values.add(cells);
                Object item=rows.get(row);secrets.add(item instanceof ManualRow manual?List.of(manual.password()==null?"":manual.password(),manual.passphrase()==null?"":manual.passphrase()):List.of("",""));
            }
            snapshot.set(new TableSnapshot(rows,values,secrets));
            SwingUtilities.invokeLater(()->{if(!renderer.isClosed()&&view.get()!=null)((AbstractTableModel)view.get().getModel()).fireTableDataChanged();});
        };
        refresh.run();
        javafx.collections.ListChangeListener listener=change->refresh.run();
        source.getItems().addListener(listener);renderer.onDispose(()->source.getItems().removeListener(listener));
        return ()->{
            JTable table=new JTable(new AbstractTableModel() {
                public int getRowCount(){return snapshot.get().rows().size();}
                public int getColumnCount(){return names.size();}
                public String getColumnName(int col){return names.get(col);}
                public Object getValueAt(int row,int col){return col==names.size()-1&&names.get(col).isBlank()?"×":snapshot.get().cells().get(row).get(col);}
                public boolean isCellEditable(int row,int col){return editable&&col>0&&col<names.size()-1;}
                public void setValueAt(Object value,int row,int col){
                    Object item=snapshot.get().rows().get(row);
                    FxThread.run(()->{
                        if(renderer.isClosed())return;
                        if(item instanceof ManualRow manual&&col==6)manual.setPassword(String.valueOf(value));
                        else if(item instanceof ManualRow manual&&col==7)manual.setPassphrase(String.valueOf(value));
                        else if(columns.get(col).getOnEditCommit()!=null) {
                            int current=source.getItems().indexOf(item);
                            columns.get(col).getOnEditCommit().handle(new TableColumn.CellEditEvent(source,new TablePosition(source,current,columns.get(col)),TableColumn.editCommitEvent(),value));
                        }
                        refresh.run();
                    });
                }
            });view.set(table);table.setName("settings.importTable");
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setRowHeight(28);table.setFillsViewportHeight(true);
            for(int i=0;i<columns.size();i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths.get(i));
            if(columns.size()>7) {
                table.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"PASSWORD","PRIVATE_KEY"})));
                for(int col:new int[]{6,7}) {
                    int index=col;
                    table.getColumnModel().getColumn(col).setCellEditor(new DefaultCellEditor(new JPasswordField()) {
                        public Component getTableCellEditorComponent(JTable t,Object value,boolean selected,int row,int column) {
                            Object item=snapshot.get().rows().get(row);
                            String actual=snapshot.get().secrets().get(row).get(index-6);
                            return super.getTableCellEditorComponent(t,actual,selected,row,column);
                        }
                    });
                }
            }
            table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){
                int row=table.rowAtPoint(event.getPoint()),col=table.columnAtPoint(event.getPoint());
                if(row>=0&&col==columns.size()-1&&names.get(col).isBlank()) {
                    Object item=snapshot.get().rows().get(row);FxThread.run(()->source.getItems().remove(item));
                }
            }});
            JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(650,220));return scroll;
        };
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
