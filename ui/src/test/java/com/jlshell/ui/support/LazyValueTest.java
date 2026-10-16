package com.jlshell.ui.support;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LazyValueTest {
    @Test void concurrentRequestsShareOneClient() throws Exception {
        var calls = new AtomicInteger();
        Object client = new Object();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lazy = new LazyValue<>(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
            return client;
        });
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(lazy::get)).toList();
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            for (var result : results) assertSame(client, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test void transientInitializationFailureCanBeRetried() {
        var attempts = new AtomicInteger();
        var lazy = new LazyValue<>(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary failure");
            return "ready";
        });
        assertThrows(IllegalStateException.class, lazy::get);
        assertEquals("ready", lazy.get());
        assertEquals("ready", lazy.get());
        assertEquals(2, attempts.get());
    }
}
