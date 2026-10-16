package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;

class RefreshableTerminalPanelImeTest {

    @Test
    void macInputMethodCanReadEmptyCommittedTextWithoutNullIterator() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var settings = new JlshellSettingsProvider(
                    new com.jlshell.core.model.FontProfile("Monospaced", 13, false, 1),
                    com.jlshell.terminal.model.TerminalColorScheme.dark());
            var style = new com.jediterm.terminal.model.StyleState();
            var panel = new RefreshableTerminalPanel(settings,
                    new com.jediterm.terminal.model.TerminalTextBuffer(80, 24, style), style, settings, key -> key);
            panel.init(new javax.swing.JScrollBar());
            try {
                var requests = panel.getInputMethodRequests();
                assertEquals(0, requests.getCommittedTextLength());
                assertEquals(0, requests.getInsertPositionOffset());
                for (var attributes : new java.text.AttributedCharacterIterator.Attribute[][]{
                        null, new java.text.AttributedCharacterIterator.Attribute[]{java.awt.font.TextAttribute.LANGUAGE}}) {
                    var text = requests.getCommittedText(0, 0, attributes);
                    assertNotNull(text);
                    // 与 CInputMethod.attributedSubstringFromRange 相同的访问顺序。
                    char[] characters = new char[text.getEndIndex() - text.getBeginIndex()];
                    assertEquals(0, characters.length);
                    assertEquals(java.text.CharacterIterator.DONE, text.first());
                    assertNotSame(text, requests.getCommittedText(0, 0, attributes));
                }
                // 无选区、不可重转换是合法的 null，与 committed-text 的契约不同。
                assertNull(requests.getSelectedText(null));
                assertNull(requests.cancelLatestCommittedText(null));
            } finally { panel.dispose(); }
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

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
