package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import javafx.event.Event;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.util.List;

/** 原有菜单动作的线程隔离适配；业务回调继续在 FX 上执行。 */
public final class SwingMenuBridge {
    private SwingMenuBridge() {}

    public record Item(String text, String shortcut, boolean disabled, boolean checked, int kind,
                       Runnable action, Runnable refresh, List<Item> children) {}

    /** 必须在 FX 线程取快照，EDT 不读取 JavaFX 的可变属性。 */
    public static List<Item> snapshot(MenuBar menuBar, java.util.function.Consumer<Runnable> dispatch) {
        return menuBar.getMenus().stream().map(menu -> snapshot(menu, dispatch)).toList();
    }

    private static Item snapshot(MenuItem item, java.util.function.Consumer<Runnable> dispatch) {
        int kind = item instanceof SeparatorMenuItem ? 1 : item instanceof CheckMenuItem ? 2
                : item instanceof RadioMenuItem ? 3 : item instanceof Menu ? 4 : 0;
        boolean checked = item instanceof CheckMenuItem check ? check.isSelected()
                : item instanceof RadioMenuItem radio && radio.isSelected();
        return new Item(item.getText(), item.getAccelerator() == null ? "" : item.getAccelerator().getDisplayText(), item.isDisable(), checked, kind, () -> dispatch.accept(() -> {
            if (item instanceof CheckMenuItem check) check.setSelected(!check.isSelected());
            if (item instanceof RadioMenuItem radio) radio.setSelected(true);
            item.fire();
        }),
                null, item instanceof Menu menu ? menu.getItems().stream().map(child -> snapshot(child, dispatch)).toList() : List.of());
    }

    public static JMenuBar create(MenuBar model, List<Item> initial, java.util.function.Consumer<Runnable> dispatch) {
        JMenuBar bar = new JMenuBar();
        for (int i = 0; i < initial.size(); i++) {
            Item spec = initial.get(i);
            JMenu menu = new JMenu(spec.text());
            fill(menu, spec.children());
            int index = i;
            menu.addMenuListener(new MenuListener() {
                public void menuSelected(MenuEvent event) {
                    FxThread.run(() -> {
                        Menu source = model.getMenus().get(index);
                        Event.fireEvent(source, new Event(Menu.ON_SHOWING));
                        List<Item> items = source.getItems().stream().map(item -> snapshot(item, dispatch)).toList();
                        SwingUtilities.invokeLater(() -> { fill(menu, items); menu.getPopupMenu().pack(); });
                    });
                }
                public void menuDeselected(MenuEvent event) {}
                public void menuCanceled(MenuEvent event) {}
            });
            bar.add(menu);
        }
        return bar;
    }

    private static void fill(JMenu target, List<Item> specs) {
        target.removeAll();
        for (Item spec : specs) {
            if (spec.kind() == 1) { target.addSeparator(); continue; }
            if (spec.kind() == 4) {
                JMenu child = new JMenu(spec.text());
                fill(child, spec.children());
                target.add(child);
                continue;
            }
            JMenuItem item = spec.kind() == 2 ? new JCheckBoxMenuItem(spec.text(), spec.checked())
                    : spec.kind() == 3 ? new JRadioButtonMenuItem(spec.text(), spec.checked()) : new JMenuItem(spec.text());
            item.setEnabled(!spec.disabled());
            item.addActionListener(event -> spec.action().run());
            target.add(item);
        }
    }
}
