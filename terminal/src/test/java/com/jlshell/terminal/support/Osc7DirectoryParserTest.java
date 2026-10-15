package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Osc7DirectoryParserTest {
    @Test void everyByteBoundaryCanSplitIntroducersAndBothTerminators() {
        String stream = "banner\033[32mprompt\033[0m\033]7;file://host/中文%20path%25+\007"
                + "output\033]7;file:///next\033\\";
        for (int chunk = 1; chunk < stream.length(); chunk++) {
            var paths = new ArrayList<String>(); var parser = new Osc7DirectoryParser(paths::add);
            char[] chars = stream.toCharArray();
            for (int offset = 0; offset < chars.length; offset += chunk)
                parser.accept(chars, offset, Math.min(chunk, chars.length - offset));
            assertEquals(List.of("/中文 path%+", "/next"), paths, "chunk=" + chunk);
        }
    }
    @Test void ignoresNonDirectoryAndMalformedSequencesAndRecovers() {
        var paths = new ArrayList<String>(); var parser = new Osc7DirectoryParser(paths::add);
        String stream = "\033]0;title\007\033]7;file://host/bad%ZZ\007\033]7;file://host/nul%00\007"
                + "\033]7;file://host/no-end" + "x".repeat(33000) + "\033]7;file://host/ok\007";
        char[] chars = stream.toCharArray(); parser.accept(chars, 0, chars.length);
        assertEquals(List.of("/ok"), paths);
    }
}
