package com.jlshell.terminal.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Streaming OSC 7 parser; escape introducers and terminators may straddle any read boundary. */
final class Osc7DirectoryParser {
    private final Consumer<String> directory;
    private final StringBuilder content = new StringBuilder();
    private boolean escape, osc;

    Osc7DirectoryParser(Consumer<String> directory) { this.directory = directory; }

    void accept(char[] chars, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            char c = chars[i];
            if (!osc) {
                if (escape && c == ']') { osc = true; content.setLength(0); escape = false; }
                else escape = c == '\033';
            } else if (c == '\007' || escape && c == '\\') {
                complete(); osc = false; escape = false;
            } else if (c == '\033') {
                escape = true;
            } else {
                if (escape) { content.append('\033'); escape = false; }
                content.append(c);
                if (content.length() > 32768) { content.setLength(0); osc = false; }
            }
        }
    }

    private void complete() {
        String value = content.toString(); content.setLength(0);
        if (!value.startsWith("7;file://")) return;
        int slash = value.indexOf('/', "7;file://".length());
        if (slash < 0) return;
        try {
            String path = URLDecoder.decode(value.substring(slash).replace("+", "%2B"), StandardCharsets.UTF_8);
            if (path.startsWith("/") && path.indexOf('\0') < 0) directory.accept(path);
        } catch (IllegalArgumentException invalidEncoding) { /* Ignore malformed remote sequences. */ }
    }
}
