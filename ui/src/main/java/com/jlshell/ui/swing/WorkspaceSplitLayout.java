package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 参照 IDEA EditorWindow 的 split/removeFromSplitter/unsplit：叶节点是独立标签组。 */
final class WorkspaceSplitLayout extends JPanel {
    private final Supplier<JTabbedPane> factory;

    WorkspaceSplitLayout(JTabbedPane initial, Supplier<JTabbedPane> factory) {
        super(new BorderLayout());
        this.factory = factory;
        setOpaque(false);
        add(initial);
    }

    List<JTabbedPane> groups() {
        var result = new ArrayList<JTabbedPane>();
        collect(getComponent(0), result);
        return result;
    }

    private static void collect(Component component, List<JTabbedPane> groups) {
        if (component instanceof JTabbedPane pane) groups.add(pane);
        else if (component instanceof JSplitPane split) {
            collect(split.getLeftComponent(), groups);
            collect(split.getRightComponent(), groups);
        }
    }

    JTabbedPane split(JTabbedPane source, int orientation) {
        Container parent = source.getParent();
        boolean first = parent instanceof JSplitPane split && split.getLeftComponent() == source;
        var target = factory.get();
        var split = new JSplitPane(orientation);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(4);
        split.setResizeWeight(.5);
        split.setContinuousLayout(true);
        replace(parent, first, split);
        split.setLeftComponent(source);
        split.setRightComponent(target);
        revalidate(); repaint();
        SwingUtilities.invokeLater(() -> { if (split.getParent() != null) split.setDividerLocation(.5); });
        return target;
    }

    private void replace(Container parent, boolean first, Component replacement) {
        if (parent instanceof JSplitPane split) {
            if (first) split.setLeftComponent(replacement); else split.setRightComponent(replacement);
        } else {
            removeAll(); add(replacement);
        }
    }

    List<JTabbedPane> siblings(JTabbedPane source) {
        if (!(source.getParent() instanceof JSplitPane parent)) return List.of();
        var result = new ArrayList<JTabbedPane>();
        collect(parent.getLeftComponent() == source ? parent.getRightComponent() : parent.getLeftComponent(), result);
        return result;
    }

    JTabbedPane groupAt(Point point) {
        for (JTabbedPane group : groups()) {
            Rectangle bounds = SwingUtilities.convertRectangle(group.getParent(), group.getBounds(), this);
            if (bounds.contains(point)) return group;
        }
        return null;
    }

    boolean inSplit(JTabbedPane group) { return group.getParent() instanceof JSplitPane; }

    void changeOrientation(JTabbedPane group) {
        if (group.getParent() instanceof JSplitPane split) {
            split.setOrientation(split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
            split.setDividerLocation(.5);
            revalidate(); repaint();
        }
    }

    /** 合并布局只转移宿主和标签头，原终端/插件子组件不重建。 */
    List<JTabbedPane> unsplit(JTabbedPane target, boolean all, java.util.function.BiConsumer<JTabbedPane, JTabbedPane> transfer) {
        if (!inSplit(target)) return List.of();
        Component branch = all ? getComponent(0) : target.getParent();
        Container parent = branch.getParent();
        boolean first = parent instanceof JSplitPane split && split.getLeftComponent() == branch;
        var removed = new ArrayList<JTabbedPane>();
        collect(branch, removed);
        removed.remove(target);
        for (JTabbedPane source : removed) transfer.accept(source, target);
        replace(parent, first, target);
        revalidate(); repaint();
        return removed;
    }

    void removeEmpty(JTabbedPane group) {
        if (group.getTabCount() != 0 || !(group.getParent() instanceof JSplitPane split)) return;
        Component sibling = split.getLeftComponent() == group ? split.getRightComponent() : split.getLeftComponent();
        Container parent = split.getParent();
        boolean first = parent instanceof JSplitPane outer && outer.getLeftComponent() == split;
        replace(parent, first, sibling);
        revalidate(); repaint();
    }
}
