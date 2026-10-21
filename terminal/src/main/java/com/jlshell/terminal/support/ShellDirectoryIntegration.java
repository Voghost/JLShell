package com.jlshell.terminal.support;

import java.time.Duration;
import java.util.UUID;

/** A bounded startup transaction: retain login output and the first prompt, hide only setup. */
final class ShellDirectoryIntegration {
    private final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String inputStart = "__jlshell_init_" + token;
    private final String inputEnd = "__jlshell_input_end_" + token;
    private final String begin = "\033]777;jlshell-begin-" + token + "\007";
    private final String redraw = "\033]777;jlshell-redraw-" + token + "\007";
    private final String end = "\033]777;jlshell-ready-" + token + "\007";
    private final StringBuilder startup = new StringBuilder();
    private final long deadline;
    private boolean pending = true;

    ShellDirectoryIntegration() { this(Duration.ofSeconds(5)); }
    ShellDirectoryIntegration(Duration timeout) { deadline = System.nanoTime() + timeout.toNanos(); }

    String command() {
        // The end marker belongs to the *end of the extra prompt*, not to the setup command.
        // This lets us retain the original login prompt without painting it a second time.
        String hook = "if [ -n \"$BASH_VERSION$ZSH_VERSION\" ]; then "
                + "if [ -n \"$ZSH_VERSION\" ] && [ -n \"${_p9k_transient_prompt-}\" ] "
                + "&& [ \"${_POWERLEVEL9K_TRANSIENT_PROMPT-off}\" != off ]; then "
                + "printf '\\033]777;jlshell-redraw-" + token + "\\007'; fi; "
                + "_jlshell_initial_prompt=1; _jlshell_ready_suffix=$(printf '\\033]777;jlshell-ready-" + token + "\\007'); "
                + "if [ -n \"$ZSH_VERSION\" ]; then _jlshell_ready_suffix=\"%{$_jlshell_ready_suffix%}\"; "
                + "else _jlshell_ready_suffix=\"\\[$_jlshell_ready_suffix\\]\"; fi; "
                + "_jlshell_osc7() { local p=\"$PWD\"; "
                + "if [ \"$_jlshell_initial_prompt\" = 1 ]; then PS1=\"$PS1$_jlshell_ready_suffix\"; _jlshell_initial_prompt=0; "
                + "else PS1=\"${PS1%\"$_jlshell_ready_suffix\"}\"; fi; "
                + "p=\"${p//\\%/%25}\"; p=\"${p// /%20}\"; "
                + "p=\"${p//$'\\n'/%0A}\"; p=\"${p//$'\\r'/%0D}\"; "
                + "printf '\\033]7;file://%s%s\\007' \"$HOSTNAME\" \"$p\"; }; "
                + "if [ -n \"$ZSH_VERSION\" ]; then typeset -ga precmd_functions; "
                + "(( ${precmd_functions[(Ie)_jlshell_osc7]} )) || precmd_functions+=(_jlshell_osc7); "
                + "else case \"$(declare -p PROMPT_COMMAND 2>/dev/null)\" in \"declare -a\"*) PROMPT_COMMAND+=(_jlshell_osc7);; "
                + "*) PROMPT_COMMAND=\"${PROMPT_COMMAND}\"$'\\n'_jlshell_osc7;; esac; fi; "
                + "else printf '\\033]777;jlshell-ready-" + token + "\\007'; fi";
        // Keep the input below small PTY canonical-line limits. The payload is our own constant
        // script; it never contains user input or remote paths. gzip/base64 are available on the
        // supported bash/zsh Unix hosts, and eval remains inside the current interactive shell.
        String script = "printf '\\033]777;jlshell-begin-" + token + "\\007'; " + hook;
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String payload = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
            return " " + inputStart + "=1; eval \"$(printf %s " + payload
                    + " | base64 -d | gzip -dc)\"; : " + inputEnd + "\n";
        } catch (java.io.IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
    }

    synchronized boolean pending() { return pending; }
    synchronized boolean expired() { return pending && System.nanoTime() >= deadline; }

    synchronized String filter(String text) {
        if (!pending) return text;
        startup.append(text);
        int done = startup.indexOf(end);
        if (done >= 0) {
            int started = startup.indexOf(begin);
            int transientRedraw = startup.indexOf(redraw);
            boolean collapse = started >= 0 && transientRedraw > started && transientRedraw < done;
            String login = stripEcho(started < 0 ? startup.substring(0, done) : startup.substring(0, started), collapse);
            String output = login + startup.substring(done + end.length());
            startup.setLength(0);
            pending = false;
            return output;
        }
        if (expired() || startup.length() > 131072) return release();
        return "";
    }

    synchronized String release() {
        pending = false;
        String output = stripEcho(startup.toString()).replace(begin, "").replace(end, "").replace(redraw, "");
        startup.setLength(0);
        return output;
    }

    /** Map visible text to raw offsets: zsh/readline insert CSI colours and wrapping in echoed input. */
    private String stripEcho(String raw) { return stripEcho(raw, false); }

    private String stripEcho(String raw, boolean discardLineFinish) {
        StringBuilder clean = new StringBuilder();
        var cleanOffsets = new java.util.ArrayList<Integer>();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) == '\033' && i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                i += 2;
                while (i < raw.length()) { char c = raw.charAt(i++); if (c >= '@' && c <= '~') break; }
            } else {
                char c = raw.charAt(i);
                clean.append(c); cleanOffsets.add(i);
                i++;
            }
        }
        StringBuilder plain = new StringBuilder();
        var offsets = new java.util.ArrayList<Integer>();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            boolean wrapRedraw = i > 0 && clean.charAt(i - 1) == '\r'
                    && i + 1 < clean.length() && clean.charAt(i + 1) == '\r';
            if (!Character.isWhitespace(c) && !wrapRedraw) { plain.append(c); offsets.add(cleanOffsets.get(i)); }
        }
        StringBuilder output = new StringBuilder();
        int scan = 0, rawOffset = 0;
        int editingStarted = raw.indexOf("\033[?2004h");
        while (true) {
            int first = plain.indexOf(inputStart, scan);
            int last = first < 0 ? -1 : plain.indexOf(inputEnd, first);
            if (last < first || first < 0) break;
            int from = offsets.get(first), to = offsets.get(last + inputEnd.length() - 1) + 1;
            if (from > rawOffset && raw.charAt(from - 1) == ' ') from--;
            while (to < raw.length() && (raw.charAt(to) == '\r' || raw.charAt(to) == '\n')) to++;
            output.append(raw, rawOffset, from).append("\033[0m");
            if (discardLineFinish && editingStarted >= 0 && from > editingStarted) return output.toString();
            rawOffset = to;
            scan = last + inputEnd.length();
        }
        // The line-finish widget erases the echoed input by moving up its wrapped row count,
        // then replaces the full prompt with a transient arrow. Neither operation is valid after
        // we removed that input from the display. Retain the complete initial prompt instead.
        if (discardLineFinish && rawOffset > 0) return output.toString();
        return output.append(raw, rawOffset, raw.length()).toString();
    }
}
