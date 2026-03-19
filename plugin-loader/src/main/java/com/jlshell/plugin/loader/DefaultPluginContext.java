package com.jlshell.plugin.loader;

import java.util.Optional;
import java.util.function.Consumer;

import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Tab;

public class DefaultPluginContext implements PluginContext {

    private final SshSession sshSession;
    private final Consumer<Tab> openTabCallback;
    private Tab openedTab;

    public DefaultPluginContext(SshSession sshSession, Consumer<Tab> openTabCallback) {
        this.sshSession = sshSession;
        this.openTabCallback = openTabCallback;
    }

    @Override
    public Optional<SshSessionContext> sshSession() {
        if (sshSession == null) {
            return Optional.empty();
        }
        return Optional.of(new SshSessionContextAdapter(sshSession));
    }

    @Override
    public void openTab(String title, Node content) {
        Platform.runLater(() -> {
            Tab tab = new Tab(title, content);
            tab.setClosable(true);
            openedTab = tab;
            openTabCallback.accept(tab);
        });
    }

    @Override
    public void closeTab() {
        if (openedTab != null) {
            Platform.runLater(() -> {
                if (openedTab.getTabPane() != null) {
                    openedTab.getTabPane().getTabs().remove(openedTab);
                }
                openedTab = null;
            });
        }
    }

    @Override
    public void showNotification(String message, NotificationLevel level) {
        Platform.runLater(() -> {
            Alert.AlertType alertType = switch (level) {
                case WARNING -> Alert.AlertType.WARNING;
                case ERROR -> Alert.AlertType.ERROR;
                default -> Alert.AlertType.INFORMATION;
            };
            Alert alert = new Alert(alertType, message);
            alert.setHeaderText(null);
            alert.show();
        });
    }
}
