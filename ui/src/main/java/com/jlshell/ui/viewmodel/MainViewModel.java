package com.jlshell.ui.viewmodel;

import com.jlshell.core.support.MutableValue;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.theme.AppTheme;
import java.util.List;

/** 主窗口状态。 */
public final class MainViewModel {
    private List<ConnectionProfile> connections = List.of();
    private final MutableValue<ConnectionProfile> selectedConnection = new MutableValue<>(null);
    private final MutableValue<String> statusMessage = new MutableValue<>("Ready");
    private final MutableValue<AppTheme> activeTheme = new MutableValue<>(AppTheme.DARK);
    public List<ConnectionProfile> connections() { return connections; }
    public MutableValue<ConnectionProfile> selectedConnectionProperty() { return selectedConnection; }
    public MutableValue<String> statusMessageProperty() { return statusMessage; }
    public MutableValue<AppTheme> activeThemeProperty() { return activeTheme; }
    public void replaceConnections(List<ConnectionProfile> items) { connections = List.copyOf(items); }
}
