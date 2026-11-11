package com.jlshell.ui.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryReclaimServiceTest {
    @Test
    void reportsCompletionOnSwingWithoutFxToolkit() throws Exception {
        try (var service = new MemoryReclaimService()) {
            var result = new CompletableFuture<String>();
            service.requestNowWithStatus(message -> {
                if (!SwingUtilities.isEventDispatchThread()) {
                    result.completeExceptionally(new AssertionError("Status callback must use EDT"));
                } else result.complete(message);
            });
            assertTrue(result.get(5, TimeUnit.SECONDS).contains("MB"));
        }
    }

    @Test
    void closedServiceStillReleasesWaitingUi() throws Exception {
        var service = new MemoryReclaimService();
        service.close();
        var result = new CompletableFuture<String>();
        service.requestNowWithStatus(result::complete);
        assertEquals("", result.get(5, TimeUnit.SECONDS));
    }
}
