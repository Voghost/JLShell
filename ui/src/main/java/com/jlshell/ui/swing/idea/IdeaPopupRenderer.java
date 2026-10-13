// Copyright 2000-2024 JetBrains s.r.o. and contributors. Apache-2.0.
// Adapted from GroupedItemsListRenderer.java, GroupedElementsRenderer.java,
// ProjectToolbarWidgetAction.kt / ProjectWidgetRenderer and SettingsEntryPointAction.java.
package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import java.awt.*;

/** IDEA 分组、双行项目条目及快捷键列的渲染结构，动作数据由宿主传入。 */
final class IdeaPopupRenderer implements ListCellRenderer<IdeaListPopup.Row> {
    private final boolean reserveIcons;
    private final IdeaListPopup.Style style;
    IdeaPopupRenderer(boolean reserveIcons) { this(reserveIcons, IdeaListPopup.Style.LIST); }
    IdeaPopupRenderer(boolean reserveIcons, IdeaListPopup.Style style) { this.reserveIcons = reserveIcons; this.style = style; }
    @Override
    public Component getListCellRendererComponent(JList<? extends IdeaListPopup.Row> list, IdeaListPopup.Row row,
                                                   int index, boolean selected, boolean focus) {
        JPanel lines = new JPanel(new BorderLayout(0, 4));
        lines.setOpaque(false);
        JLabel name = new JLabel(row.title());
        name.putClientProperty("html.disable", true);
        name.setForeground(row.enabled() ? list.getForeground() : SwingTheme.color("secondary"));
        name.setFont(list.getFont());
        lines.add(name, row.detail() == null ? BorderLayout.CENTER : BorderLayout.NORTH);
        if (row.detail() != null && !row.detail().isBlank()) {
            JLabel description = new JLabel(row.detail());
            description.putClientProperty("html.disable", true);
            description.setFont(list.getFont().deriveFont(Math.max(11f, list.getFont().getSize2D() - 1)));
            description.setForeground(SwingTheme.color("secondary"));
            lines.add(description, BorderLayout.SOUTH);
        }
        JPanel content = new JPanel(new BorderLayout(style == IdeaListPopup.Style.CONTEXT ? 8 : 6, 0));
        content.setOpaque(false);
        int side = style == IdeaListPopup.Style.CONTEXT ? 12 : 16;
        content.setBorder(BorderFactory.createEmptyBorder(row.detail() == null ? 0 : 6, side,
                row.detail() == null ? 0 : 6, side));
        JLabel icon = new JLabel(row.icon());
        icon.setPreferredSize(new Dimension(row.detail() == null ? 16 : 24, row.detail() == null ? 16 : 24));
        icon.setVerticalAlignment(row.detail() == null ? SwingConstants.CENTER : SwingConstants.TOP);
        if (reserveIcons || row.icon() != null) content.add(icon, BorderLayout.WEST);
        content.add(lines, BorderLayout.CENTER);
        JLabel trailing = new JLabel(!row.children().isEmpty() ? "›" : row.shortcut());
        trailing.setFont(list.getFont());
        trailing.setForeground(SwingTheme.color("secondary"));
        trailing.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        content.add(trailing, BorderLayout.EAST);
        SelectablePanel item = SelectablePanel.wrap(content, list.getBackground());
        if (row.detail() == null) item.preferredHeight = Math.max(style == IdeaListPopup.Style.CONTEXT ? 22 : 24,
                list.getFontMetrics(list.getFont()).getHeight() + (style == IdeaListPopup.Style.CONTEXT ? 6 : 4));
        item.selectionArc = style == IdeaListPopup.Style.CONTEXT ? 6 : 8;
        item.selectionInsets = new Insets(0, style == IdeaListPopup.Style.CONTEXT ? 6 : 8, 0,
                style == IdeaListPopup.Style.CONTEXT ? 6 : 8);
        item.selectionColor = selected && row.enabled() ? SwingTheme.color("popupSelection") : null;
        item.getAccessibleContext().setAccessibleName(row.title());
        if (row.group() == null) return item;
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(list.getBackground());
        var separator = new IdeaGroupHeaderSeparator(row.group(), index == 0, list.getFont());
        wrapper.add(separator, BorderLayout.NORTH);
        wrapper.add(item, BorderLayout.CENTER);
        return wrapper;
    }
}
