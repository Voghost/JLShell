package com.jlshell.terminal.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalEchoSuppressorTest {

    @Test
    void removesEchoSplitAcrossReadsAndKeepsLineEndingAndPrompt() {
        TerminalEchoSuppressor suppressor = new TerminalEchoSuppressor();
        suppressor.suppressNext(": __JLSHELL_OSC7_SETUP__; hook\n");

        assertEquals("banner\r\nprompt$ ", filter(suppressor, "banner\r\nprompt$ "));
        assertEquals("", filter(suppressor, ": __JLSHELL_"));
        assertEquals("", filter(suppressor, "OSC7_SETUP__; hook"));
        assertEquals("\r\nprompt$ ", filter(suppressor, "\r\nprompt$ "));
    }

    @Test
    void restoresPartialCandidateWhenStreamDoesNotMatch() {
        TerminalEchoSuppressor suppressor = new TerminalEchoSuppressor();
        suppressor.suppressNext("internal-command\n");

        assertEquals("", filter(suppressor, "internal-"));
        assertEquals("internal-message", filter(suppressor, "message"));
        assertEquals("\r\n", filter(suppressor, "\r\n"));
    }

    @Test
    void onlySuppressesOneOccurrence() {
        TerminalEchoSuppressor suppressor = new TerminalEchoSuppressor();
        suppressor.suppressNext("hidden\n");

        assertEquals("hidden", filter(suppressor, "hiddenhidden"));
    }

    private static String filter(TerminalEchoSuppressor suppressor, String value) {
        char[] chars = value.toCharArray();
        return suppressor.filter(chars, 0, chars.length);
    }
}
