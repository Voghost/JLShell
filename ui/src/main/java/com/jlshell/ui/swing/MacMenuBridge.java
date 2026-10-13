package com.jlshell.ui.swing;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.skin.MenuBarSkin;
import java.util.ArrayList;
import java.util.List;

/**
 * 对照 MacNativeActionMenu 的原生菜单/动作模型分离。
 * OpenJDK 的 Aqua 在 Glass 嵌入模式下拒绝屏幕菜单，因此通过现有 JavaFX 原生菜单后端承载。
 */
public final class MacMenuBridge {
    private static MenuBar installed;
    private MacMenuBridge() {}
    public static boolean isInstalled() { return installed != null; }
    public static void install(MenuBar model) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Native menu model requires FX thread");
        MenuBarSkin.setDefaultSystemMenuBar(model);
        for (Menu menu : model.getMenus()) menu.addEventHandler(Menu.ON_SHOWING, e -> refreshAccelerators(menu));
        // setDefaultSystemMenuBar 仅配置失焦后的菜单模型，Swing 主窗口没有 FX Stage 焦点事件。
        // 需要显式发布初始原生菜单；使用反射限定兼容后端，主应用仍按既有 fat JAR/classpath 启动。
        try {
            Class<?> toolkitType = Class.forName("com.sun.javafx.tk.Toolkit");
            Object toolkit = toolkitType.getMethod("getToolkit").invoke(null);
            Object system = toolkitType.getMethod("getSystemMenu").invoke(toolkit);
            Class<?> systemType = Class.forName("com.sun.javafx.tk.TKSystemMenu");
            if (!Boolean.TRUE.equals(systemType.getMethod("isSupported").invoke(system))) return;
            var adapt = Class.forName("com.sun.javafx.scene.control.GlobalMenuAdapter").getMethod("adapt", Menu.class);
            List<Object> menus = new ArrayList<>();
            for (Menu menu : model.getMenus()) { refreshAccelerators(menu); menus.add(adapt.invoke(null, menu)); }
            systemType.getMethod("setMenus", List.class).invoke(system, menus);
            installed = model;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialize macOS native menus", error);
        }
    }
    private static void refreshAccelerators(Menu menu) {
        for (MenuItem item : menu.getItems()) {
            if (item.getProperties().get("jlshell.shortcut") instanceof java.util.function.Supplier<?> source) {
                item.setAccelerator(com.jlshell.ui.shortcut.FxShortcutConverter.toKeyCodeCombination((String) source.get()));
            }
            if (item instanceof Menu child) refreshAccelerators(child);
        }
    }
}
