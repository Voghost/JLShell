package com.jlshell.ui.swing;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.util.List;
import java.util.function.Supplier;

/** 原生菜单由不可变动作快照构建，打开时刷新动态状态。 */
public final class SwingMenuBridge {
    private SwingMenuBridge() {}
    public record Item(String text, String shortcut, KeyStroke accelerator, boolean disabled, boolean checked, int kind,
                       Runnable action, Runnable refresh, List<Item> children) {}

    public static Item action(String text, Runnable action) { return new Item(text, "", null, false, false, 0, action, null, List.of()); }
    public static Item disabled(String text) { return new Item(text, "", null, true, false, 0, () -> {}, null, List.of()); }
    public static Item separator() { return new Item("", "", null, false, false, 1, () -> {}, null, List.of()); }
    public static Item checked(String text, boolean selected, Runnable action) { return new Item(text, "", null, false, selected, 2, action, null, List.of()); }
    public static Item submenu(String text, List<Item> children) { return new Item(text, "", null, false, false, 4, () -> {}, null, children); }

    public static JMenuBar create(Supplier<List<Item>> provider) {
        JMenuBar bar = new JMenuBar();
        List<Item> initial = provider.get();
        for (int i = 0; i < initial.size(); i++) {
            Item spec = initial.get(i);
            JMenu menu = new JMenu(spec.text()); fill(menu, spec.children());
            int index = i;
            menu.addMenuListener(new MenuListener() {
                public void menuSelected(MenuEvent event) {
                    List<Item> current = provider.get();
                    if (index < current.size()) fill(menu, current.get(index).children());
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
            item.setAccelerator(spec.accelerator());
            item.setEnabled(!spec.disabled());
            item.addActionListener(event -> spec.action().run());
            target.add(item);
        }
    }
}
