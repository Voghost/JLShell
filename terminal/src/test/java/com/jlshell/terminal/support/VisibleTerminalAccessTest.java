package com.jlshell.terminal.support;

import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.plugin.api.host.TerminalAccess;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VisibleTerminalAccessTest {
    @Test void snapshotsAreBoundedAndSubscriptionsSeeSplitCommandMarkers() throws Exception {
        var widget=SwingExecutors.supplyOnEdtAsync(() -> JlshellJediTermWidget.create(80,12,
                new JlshellSettingsProvider(new FontProfile("Monospaced",14,false,1),TerminalColorScheme.dark()),key -> key)).get();
        var access=new VisibleTerminalAccess("test-session",widget,() -> "/tmp");
        try {
            SwingExecutors.runOnEdtAsync(() -> widget.getTerminal().writeCharacters("visible terminal output")).get();
            var snapshot=access.snapshot(0,12).get(2,TimeUnit.SECONDS);
            assertEquals("test-session",snapshot.sessionId());
            assertTrue(snapshot.screen().length()+snapshot.history().length()<=12);
            assertTrue(snapshot.truncated());
            assertThrows(ExecutionException.class,() -> access.snapshot(-1,100).get());
            var events=new LinkedBlockingQueue<TerminalAccess.Event>();
            var subscription=access.subscribe(events::add);
            access.publish(TerminalAccess.EventKind.OUTPUT,"\033]133;");
            access.publish(TerminalAccess.EventKind.OUTPUT,"C\007command output\033]133;D;7\007");
            TerminalAccess.Event finished=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline) {
                var event=events.poll(100,TimeUnit.MILLISECONDS);
                if(event!=null && event.kind()==TerminalAccess.EventKind.COMMAND_FINISHED) { finished=event; break; }
            }
            assertNotNull(finished);
            assertEquals(7,finished.exitCode());
            subscription.close();
            String largeOutput = "x".repeat(100000);
            long after = access.events(0, 256).nextSequence();
            access.publish(TerminalAccess.EventKind.OUTPUT, largeOutput);
            var replay = access.events(after, 256);
            assertEquals(largeOutput, replay.events().stream().map(TerminalAccess.Event::text)
                    .collect(java.util.stream.Collectors.joining()));
            assertTrue(replay.events().stream().allMatch(event -> event.text().length() <= 65536));
            for (int index = 0; index < 300; index++) access.publish(TerminalAccess.EventKind.OUTPUT, "chunk");
            assertTrue(access.events(after, 256).truncated());
        } finally { access.close(); SwingExecutors.runOnEdtAsync(widget::close).get(); }
        assertThrows(ExecutionException.class,() -> access.paste("test").get());
    }
}
