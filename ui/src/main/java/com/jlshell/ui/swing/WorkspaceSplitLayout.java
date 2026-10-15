package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 参照 IDEA EditorWindow 的 split/removeFromSplitter/unsplit：叶节点是独立标签组。 */
final class WorkspaceSplitLayout extends JPanel {
    enum DropSide { CENTER, LEFT, RIGHT, TOP, BOTTOM }
    record DropLocation(JTabbedPane group, DropSide side, Rectangle preview) {}

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
        return split(source, orientation, false);
    }

    JTabbedPane split(JTabbedPane source, int orientation, boolean newGroupFirst) {
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
        split.setLeftComponent(newGroupFirst ? target : source);
        split.setRightComponent(newGroupFirst ? source : target);
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

    /** IDEA-style edge drop zones: the outer 20% of a group creates a split; the center transfers tabs. */
    DropLocation dropLocation(Point point) {
        JTabbedPane group = groupAt(point);
        if (group == null) return null;
        Rectangle bounds = SwingUtilities.convertRectangle(group.getParent(), group.getBounds(), this);
        Point local = new Point(point.x - bounds.x, point.y - bounds.y);
        double edge = .20;
        double left = local.x / (double) Math.max(1, bounds.width);
        double right = (bounds.width - local.x) / (double) Math.max(1, bounds.width);
        Rectangle content = group.getSelectedComponent() == null ? new Rectangle(0, 0, bounds.width, bounds.height)
                : group.getSelectedComponent().getBounds();
        // 整条标签头（含两端和空白处）只接受排序/跨组移动；分屏必须进入正文。
        // 预览与释放共用这一命中结果，避免头部拖动时误显示或提交左右分屏。
        if (!content.contains(local)) return new DropLocation(group, DropSide.CENTER, new Rectangle(bounds));
        double top = local.y >= content.y && local.y <= content.y + content.height
                ? (local.y - content.y) / (double) Math.max(1, content.height) : Double.POSITIVE_INFINITY;
        double bottom = local.y >= content.y && local.y <= content.y + content.height
                ? (content.y + content.height - local.y) / (double) Math.max(1, content.height) : Double.POSITIVE_INFINITY;
        DropSide side = DropSide.CENTER;
        double nearest = edge;
        if (left < nearest) { nearest = left; side = DropSide.LEFT; }
        if (right < nearest) { nearest = right; side = DropSide.RIGHT; }
        if (top < nearest) { nearest = top; side = DropSide.TOP; }
        if (bottom < nearest) { side = DropSide.BOTTOM; }
        Rectangle preview = new Rectangle(bounds);
        switch (side) {
            case LEFT -> preview.width = Math.max(1, bounds.width / 2);
            case RIGHT -> { preview.x += bounds.width / 2; preview.width = bounds.width - bounds.width / 2; }
            case TOP -> preview.height = Math.max(1, bounds.height / 2);
            case BOTTOM -> { preview.y += bounds.height / 2; preview.height = bounds.height - bounds.height / 2; }
            case CENTER -> { }
        }
        return new DropLocation(group, side, preview);
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
