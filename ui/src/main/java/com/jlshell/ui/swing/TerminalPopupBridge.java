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
        install(component, null, null);
    }
    public static void install(Component component, String terminalSettingsLabel, Runnable terminalSettings) {
        if (component instanceof RefreshableTerminalPanel panel) panel.setPopupPresenter((invoker, x, y, actions) ->
                show(invoker, x, y, actions, terminalSettingsLabel, terminalSettings));
        if (component instanceof Container container) for (Component child : container.getComponents())
            install(child, terminalSettingsLabel, terminalSettings);
    }
    private static void show(Component invoker, int x, int y, List<RefreshableTerminalPanel.PopupAction> actions,
                             String terminalSettingsLabel, Runnable terminalSettings) {
        List<IdeaListPopup.Row> rows = new ArrayList<>();
        String group = null;
        for (var action : actions) {
            if (action.separator()) { group = ""; continue; }
            rows.add(new IdeaListPopup.Row(action.label(), null, null, action.shortcut(), group,
                    action.enabled(), List.of(), action.action()));
            group = null;
        }
        if (terminalSettings != null) {
            rows.add(new IdeaListPopup.Row(terminalSettingsLabel, null, null, "", "",
                    true, List.of(), terminalSettings));
        }
        new IdeaListPopup(rows, 190, false, "").show(invoker, x, y);
    }
}
