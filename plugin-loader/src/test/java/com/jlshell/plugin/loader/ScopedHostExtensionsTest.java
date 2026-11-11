package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.lifecycle.Registration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScopedHostExtensionsTest {
    @Test void onlyMatchingSessionSeesActionsAndDisposeRemovesThem() {
        ScopedHostExtensions scoped=new ScopedHostExtensions("sdk-regression-session");
        var action=new HostExtensions.FileAction("test","Test",null,null,selection -> true,selection -> true,selection -> {});
        scoped.registerFileAction(action);
        assertTrue(HostExtensionRegistry.fileActions("sdk-regression-session").contains(action));
        assertFalse(HostExtensionRegistry.fileActions("other-session").contains(action));
        scoped.close(); scoped.close();
        assertFalse(HostExtensionRegistry.fileActions("sdk-regression-session").contains(action));
        assertThrows(IllegalStateException.class,() -> scoped.registerFileAction(action));
        assertFalse(HostExtensionRegistry.fileActions("sdk-regression-session").contains(action));
    }
    @Test void owningTheSameResourceTwiceReleasesItOnce() {
        var context=new ScopedHostExtensions(null);
        AtomicInteger closed=new AtomicInteger();
        AutoCloseable resource=closed::incrementAndGet;
        context.own(resource); context.own(resource); context.close();
        assertEquals(1,closed.get());
    }
    @Test void closingSessionStillUnregistersAfterActiveStatusChanged() {
        String id="active-state-disposal";
        var terminal=org.mockito.Mockito.mock(com.jlshell.plugin.api.host.TerminalAccess.class);
        var registration=HostExtensionRegistry.terminal(new HostExtensions.Session(id,"Test","host","user",true,false),terminal);
        HostExtensionRegistry.activeSession(id);
        registration.close();
        assertTrue(HostExtensionRegistry.sessions().stream().noneMatch(session -> session.id().equals(id)));
        assertTrue(HostExtensionRegistry.terminal(id).isEmpty());
    }
    @Test void releasesResourcesInReverseOrderEvenWhenOneFails() {
        ScopedHostExtensions scoped=new ScopedHostExtensions(null);
        var order=new java.util.ArrayList<Integer>();
        scoped.own((Registration) () -> order.add(1));
        scoped.own((Registration) () -> { order.add(2); throw new IllegalStateException("test"); });
        scoped.own((Registration) () -> order.add(3));
        scoped.close();
        assertEquals(List.of(3,2,1),order);
        AtomicInteger released=new AtomicInteger();
        assertThrows(IllegalStateException.class,() -> scoped.own((Registration) released::incrementAndGet));
        assertEquals(1,released.get());
    }
}
