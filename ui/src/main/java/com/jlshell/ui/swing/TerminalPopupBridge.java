package com.jlshell.ui.swing;

import com.jlshell.terminal.support.RefreshableTerminalPanel;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/** 沿用 JediTerm 原动作，只替换菜单展示；terminal 不依赖 UI 模块。 */
public final class TerminalPopupBridge {
    private TerminalPopupBridge() {}
    public static void install(Component component) {
        install(component, null, null, null, null);
    }
    public static void install(Component component, String terminalSettingsLabel, Runnable terminalSettings,
                               String pluginLabel, Runnable pluginSettings) {
        if (component instanceof RefreshableTerminalPanel panel) panel.setPopupPresenter((invoker, x, y, actions) ->
                show(invoker, x, y, actions, terminalSettingsLabel, terminalSettings, pluginLabel, pluginSettings));
        if (component instanceof Container container) for (Component child : container.getComponents())
            install(child, terminalSettingsLabel, terminalSettings, pluginLabel, pluginSettings);
    }
    private static void show(Component invoker, int x, int y, List<RefreshableTerminalPanel.PopupAction> actions,
                             String terminalSettingsLabel, Runnable terminalSettings,
                             String pluginLabel, Runnable pluginSettings) {
        List<IdeaListPopup.Row> rows = new ArrayList<>();
        String group = null;
        for (var action : actions) {
            if (action.separator()) { group = ""; continue; }
            rows.add(new IdeaListPopup.Row(action.label(), null, null, action.shortcut(), group,
                    action.enabled(), List.of(), action.action()));
            group = null;
        }
        if (terminalSettings != null || pluginSettings != null) {
            group = "";
            if (terminalSettings != null) {
                rows.add(new IdeaListPopup.Row(terminalSettingsLabel, null, null, "", group,
                        true, List.of(), terminalSettings));
                group = null;
            }
            if (pluginSettings != null) rows.add(new IdeaListPopup.Row(pluginLabel, null, null, "", group,
                    true, List.of(), pluginSettings));
        }
        new IdeaListPopup(rows, 190, false, "").show(invoker, x, y);
    }
}
