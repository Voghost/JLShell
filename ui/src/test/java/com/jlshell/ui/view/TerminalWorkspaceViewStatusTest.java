package com.jlshell.ui.view;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalWorkspaceViewStatusTest {

    @Test
    void parsesRemoteServerTimezoneOffsets() {
        assertEquals(ZoneOffset.ofHours(8), TerminalWorkspaceView.parseRemoteZoneOffset("+0800"));
        assertEquals(ZoneOffset.of("-05:30"), TerminalWorkspaceView.parseRemoteZoneOffset("-0530"));
        assertEquals(ZoneOffset.UTC, TerminalWorkspaceView.parseRemoteZoneOffset("invalid"));
    }

    @Test
    void normalizesResourceThresholdsAndKeepsDangerAboveWarning() {
        assertEquals(new TerminalWorkspaceView.ResourceThresholds(70, 90),
                TerminalWorkspaceView.ResourceThresholds.of(70, 90));
        assertEquals(new TerminalWorkspaceView.ResourceThresholds(98, 99),
                TerminalWorkspaceView.ResourceThresholds.of(120, 40));
        assertEquals(new TerminalWorkspaceView.ResourceThresholds(1, 2),
                TerminalWorkspaceView.ResourceThresholds.of(-10, -5));
    }

    @Test
    void parsesAndLimitsTopProcessRows() {
        StringBuilder output = new StringBuilder("header that should be ignored\n");
        for (int i = 1; i <= 12; i++) {
            output.append(i)
                    .append(" user")
                    .append(i)
                    .append(' ')
                    .append(i + 0.5)
                    .append(' ')
                    .append(i + 1.5)
                    .append(' ')
                    .append(i * 1024)
                    .append(" /usr/bin/process-")
                    .append(i)
                    .append(" --worker\n");
        }

        List<TerminalWorkspaceView.ProcessUsage> processes =
                TerminalWorkspaceView.parseTopProcesses(output.toString());

        assertEquals(10, processes.size());
        assertEquals("1", processes.getFirst().pid());
        assertEquals("user1", processes.getFirst().user());
        assertEquals(1.5, processes.getFirst().cpuPercent());
        assertEquals(2.5, processes.getFirst().memPercent());
        assertEquals(1024L * 1024, processes.getFirst().residentBytes());
        assertEquals("/usr/bin/process-1 --worker", processes.getFirst().command());
        assertEquals("10", processes.getLast().pid());
    }
}
