package com.jlshell.core.support;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecentApplicationLogsTest {
    @Test void boundsEntriesAndMessagesAndUnsubscribes() throws Exception {
        var logs = new RecentApplicationLogs(); var count = new AtomicInteger();
        var listener = logs.subscribe(count::incrementAndGet);
        for (int i=0;i<250;i++) logs.append(Instant.EPOCH,"INFO","test","entry-"+i);
        var snapshot=logs.snapshot(); assertEquals(200,snapshot.size());
        assertEquals("entry-249",snapshot.getFirst().message()); assertEquals("entry-50",snapshot.getLast().message());
        logs.append(Instant.EPOCH,"ERROR","test","x".repeat(20000));
        assertTrue(logs.snapshot().getFirst().message().length()<=8193);
        assertEquals("entry-249",snapshot.getFirst().message());
        listener.close(); logs.append(Instant.EPOCH,"INFO","test","after close"); assertEquals(251,count.get());
    }
}
