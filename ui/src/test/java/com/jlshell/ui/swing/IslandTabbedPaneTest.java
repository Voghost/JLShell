package com.jlshell.ui.swing;

import com.jlshell.ui.swing.idea.IdeaListPopup;
import com.jlshell.ui.swing.idea.SelectablePanel;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class IslandTabbedPaneTest {
    private static List<Component> descendants(Component root) {
        List<Component> result = new ArrayList<>(); result.add(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(descendants(child));
        return result;
    }

    @Test void overflowUsesRoundedListHoverAndKeepsDuplicateTabsDistinct() throws Exception {
        for (AppTheme theme : List.of(AppTheme.DARK, AppTheme.LIGHT)) SwingUtilities.invokeAndWait(() -> {
            SwingTheme.install(theme, "#3574f0", "SansSerif", 13);
            JFrame frame = new JFrame();
            IslandTabbedPane tabs = new IslandTabbedPane();
            try {
                tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
                for (int index = 0; index < 8; index++) tabs.addTab("nas 的服务器——生产环境与测试服务器的超长连接名称".repeat(2), new JPanel());
                tabs.setSelectedIndex(7);
                frame.add(tabs); frame.setSize(660, 300); frame.setLocationRelativeTo(null); frame.setVisible(true); frame.validate();
                JButton more = descendants(tabs).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> "tabs.overflowButton".equals(button.getName())).findFirst().orElseThrow();
                assertTrue(more.isShowing());
                JViewport viewport = descendants(tabs).stream().filter(JViewport.class::isInstance).map(JViewport.class::cast).findFirst().orElseThrow();
                Rectangle visible = SwingUtilities.convertRectangle(viewport, new Rectangle(viewport.getSize()), tabs);
                List<Component> hidden = new ArrayList<>();
                for (int index = 0; index < tabs.getTabCount(); index++) if (!visible.contains(tabs.getBoundsAt(index))) hidden.add(tabs.getComponentAt(index));
                more.doClick();
                Window window = java.util.Arrays.stream(frame.getOwnedWindows()).filter(Window::isShowing).findFirst().orElseThrow();
                assertEquals("idea.popupWindow", window.getName());
                assertTrue(descendants(window).stream().noneMatch(JPopupMenu.class::isInstance));
                JList<?> list = descendants(window).stream().filter(JList.class::isInstance).map(JList.class::cast).findFirst().orElseThrow();
                assertEquals(hidden.size(), list.getModel().getSize());
                assertTrue(list.getWidth() <= 420, "Long tab names must not create a very wide popup");
                int target = Math.min(1, list.getModel().getSize() - 1);
                Rectangle cell = list.getCellBounds(target, target);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, cell.x + 10, cell.y + cell.height / 2, 0, false));
                assertEquals(target, list.getSelectedIndex(), "Mouse hover must use the common list selection");
                assertNotNull(list.getToolTipText(new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0, cell.x + 10, cell.y + 2, 0, false)));
                @SuppressWarnings("unchecked") var typedList = (JList<IdeaListPopup.Row>) list;
                Component renderer = typedList.getCellRenderer().getListCellRendererComponent(typedList, typedList.getModel().getElementAt(target), target, true, false);
                assertTrue(descendants(renderer).stream().anyMatch(component -> component instanceof SelectablePanel panel
                        && SwingTheme.color("popupSelection").equals(panel.selectionColor) && panel.selectionArc > 0));
                BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics(); window.paintAll(g); g.dispose();
                Path output = Path.of("target/review/tabs-overflow-" + theme.name().toLowerCase() + ".png");
                Files.createDirectories(output.getParent()); javax.imageio.ImageIO.write(image, "png", output.toFile());
                Component expected = hidden.get(target);
                // 用户可能在弹窗打开期间关闭别的标签；选择必须仍按原组件身份定位。
                if (tabs.getComponentAt(0) != expected) tabs.removeTabAt(0);
                list.getActionMap().get("ENTER").actionPerformed(new java.awt.event.ActionEvent(list, 0, "ENTER"));
                assertSame(expected, tabs.getSelectedComponent());
                assertFalse(window.isShowing());
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            finally { frame.dispose(); }
        });
    }

    @Test void selectedRowsRenderAVectorCheckInsteadOfAFontGlyph() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            var popup = new IdeaListPopup(List.of(IdeaListPopup.Row.action("当前项目", null, "✓", null, () -> {})), 240, false, "");
            @SuppressWarnings("unchecked") JList<IdeaListPopup.Row> list = (JList<IdeaListPopup.Row>) descendants(popup).stream()
                    .filter(JList.class::isInstance).findFirst().orElseThrow();
            Component renderer = list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(0), 0, true, false);
            assertTrue(descendants(renderer).stream().anyMatch(component -> component instanceof JLabel label
                    && label.getIcon() instanceof WorkbenchIcon icon && icon.kind() == WorkbenchIcon.Kind.CHECK));
            assertFalse(descendants(renderer).stream().anyMatch(component -> component instanceof JLabel label && "✓".equals(label.getText())));
        });
    }
}
