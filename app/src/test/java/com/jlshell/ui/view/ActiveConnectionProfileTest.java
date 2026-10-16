package com.jlshell.ui.view;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveConnectionProfileTest {
    @Test void sshTabsResolveTheirProfileWithoutUserData() {
        ConnectionProfile profile = profile("ssh-profile");
        SessionWorkspaceTab tab = mock(SessionWorkspaceTab.class);
        when(tab.getConnectionProfile()).thenReturn(profile);
        assertNull(tab.getUserData());
        assertSame(profile, MainWindow.connectionProfileForTab(tab));
    }
    @Test void localTabsResolveTheirUserDataAndNonSessionTabsHaveNoProfile() {
        Tab tab = new Tab();
        ConnectionProfile profile = profile("local-profile");
        tab.setUserData(profile);
        assertSame(profile, MainWindow.connectionProfileForTab(tab));
        assertNull(MainWindow.connectionProfileForTab(new Tab()));
        assertNull(MainWindow.connectionProfileForTab(null));
    }
    private ConnectionProfile profile(String id) {
        return new ConnectionProfile(id, "Same name", "192.0.2.1", 22, "user", null, null,
                "", "", false, null, ConnectionType.SSH, null, null);
    }
}
