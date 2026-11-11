package com.jlshell.core.support;

import com.jlshell.plugin.api.lifecycle.Registration;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MutableValueTest {
    @Test void publishesInitialDistinctValuesAndStopsAfterClose() {
        var value = new MutableValue<String>("dark");
        var received = new ArrayList<String>();
        Registration subscription = value.subscribe(received::add);
        value.set("dark");
        value.set("light");
        subscription.close();
        subscription.close();
        value.set("dark");
        assertEquals(List.of("dark", "light"), received);
    }

    @Test void reentrantPublicationKeepsEverySubscriberInOrder() {
        var value = new MutableValue<Integer>(0);
        var received = new ArrayList<Integer>();
        value.subscribe(next -> { if (next == 1) value.set(2); });
        value.subscribe(received::add);
        value.set(1);
        assertEquals(List.of(0, 1, 2), received);
    }

    @Test void listenerFailureDoesNotBreakOtherSubscribersOrLaterChanges() {
        var value = new MutableValue<Integer>(0);
        value.subscribe(next -> { throw new IllegalStateException("broken plugin"); });
        var received = new ArrayList<Integer>();
        value.subscribe(received::add);
        value.set(1);
        value.set(2);
        assertEquals(List.of(0, 1, 2), received);
    }

    @Test void removesSubscriptionBeforeQueuedDelivery() {
        var value = new MutableValue<Integer>(0);
        Registration[] subscription = new Registration[1];
        value.subscribe(next -> { if (next == 1) subscription[0].close(); });
        var received = new ArrayList<Integer>();
        subscription[0] = value.subscribe(received::add);
        value.set(1);
        assertEquals(List.of(0), received);
    }

    @Test void bindingUsesLatestSnapshotAndCanBeReplacedOrReleased() {
        var source = new MutableValue<String>("dark");
        var other = new MutableValue<String>("system");
        var value = new MutableValue<String>(null);
        value.bind(source);
        assertEquals("dark", value.get());
        assertThrows(IllegalStateException.class, () -> value.set("light"));
        value.bind(other);
        source.set("light");
        assertEquals("system", value.get());
        value.unbind();
        other.set("dark");
        value.set("local");
        assertEquals("local", value.get());
        assertFalse(value.isBound());
        assertThrows(IllegalArgumentException.class, () -> value.bind(value));
    }

    @Test void concurrentWritesStayOrderedAcrossSubscribers() throws Exception {
        var value = new MutableValue<Integer>(-1);
        var first = new ArrayList<Integer>();
        var second = new ArrayList<Integer>();
        value.subscribe(first::add);
        value.subscribe(second::add);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 100; index++) {
                int next = index;
                executor.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                    value.set(next);
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(101, first.size());
        assertEquals(first, second);
        assertEquals(value.get(), first.getLast());
    }
}
