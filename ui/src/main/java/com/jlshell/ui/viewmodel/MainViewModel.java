package com.jlshell.ui.viewmodel;

import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.theme.AppTheme;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 主窗口 ViewModel。
 */
public class MainViewModel {

    private final ObservableList<ConnectionProfile> connections = FXCollections.observableArrayList();
    private final ObjectProperty<ConnectionProfile> selectedConnection = new SimpleObjectProperty<>();
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");
    private final ObjectProperty<AppTheme> activeTheme = new SimpleObjectProperty<>(AppTheme.DARK);

    public ObservableList<ConnectionProfile> connections() {
        return connections;
    }

    public ObjectProperty<ConnectionProfile> selectedConnectionProperty() {
        return selectedConnection;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public ObjectProperty<AppTheme> activeThemeProperty() {
        return activeTheme;
    }

    public void replaceConnections(java.util.List<ConnectionProfile> items) {
        connections.setAll(items);
    }
}
