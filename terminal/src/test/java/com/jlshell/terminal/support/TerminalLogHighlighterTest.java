package com.jlshell.terminal.support;

import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.model.TerminalHighlightConfig.Rule;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerminalLogHighlighterTest {
    @Test void colorsLevelsWithoutMatchingInsideWordsAndRestoresConfiguration() {
        var config = TerminalHighlightConfig.defaults();
        assertEquals(config, TerminalHighlightConfig.parse(config.serialize()));
        String text = "INFO WARN WARNING ERROR FATAL debug TRACE SUCCESS INFORM ERRORCode";
        var spans = new TerminalLogHighlighter(config).match(text);
        assertEquals(List.of("INFO", "WARN", "WARNING", "ERROR", "FATAL", "debug", "TRACE", "SUCCESS"),
                spans.stream().map(s -> text.substring(s.start(), s.end())).toList());
        assertEquals(Color.decode("#FBBF24"), spans.get(1).color());
        assertEquals(Color.decode("#F87171"), spans.get(3).color());
        assertEquals(config, TerminalHighlightConfig.parse("{broken"));
        assertEquals(config, TerminalHighlightConfig.parse("{\"enabled\":true,\"rules\":null}"));
    }
    @Test void supportsLiteralUnicodeDisabledRulesCaseAndPriority() {
        var first = new Rule("first", true, false, true, "失败.[", "#123456");
        var second = new Rule("second", true, true, false, "失败", "#FF0000");
        var config = new TerminalHighlightConfig(true, List.of(first, second,
                new Rule("disabled", false, false, true, "INFO", "#123456")));
        var engine = new TerminalLogHighlighter(config);
        var matches = engine.match("失败.[ INFO");
        assertEquals(List.of(new TerminalLogHighlighter.Span(0, 4, Color.decode("#123456"))), matches);
        assertTrue(new TerminalLogHighlighter(new TerminalHighlightConfig(false, config.rules())).match("失败.[").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new TerminalLogHighlighter(new TerminalHighlightConfig(true,
                List.of(new Rule("broken", true, true, true, "[", "#123456")))));
    }
    @Test void boundsPathologicalRegexAndAcceptsZeroLengthPatterns() {
        var slow = new TerminalLogHighlighter(new TerminalHighlightConfig(true, List.of(
                new Rule("bad", true, true, false, "(a+)+$", "#FF0000"))));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(slow.match("a".repeat(8000)+"!").isEmpty()));
        var empty = new TerminalLogHighlighter(new TerminalHighlightConfig(true, List.of(
                new Rule("zero", true, true, false, "^|$", "#FF0000"))));
        assertTrue(empty.match("text").isEmpty());
    }
}
