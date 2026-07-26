package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshableTerminalPanelImeTest {

    @Test
    void placesCandidateAtBottomOfOneBasedCursorRow() {
        Point anchor = RefreshableTerminalPanel.cursorAnchor(12, 3, 9, 20, 4);

        assertEquals(new Point(112, 60), anchor);
    }

    @Test
    void clampsUninitializedCoordinatesAndMetrics() {
        Point anchor = RefreshableTerminalPanel.cursorAnchor(-1, -1, -1, -1, -1);

        assertEquals(new Point(0, 0), anchor);
    }
}
