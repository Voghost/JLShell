package com.jlshell.terminal.support;

import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ShellDirectoryIntegrationTest {
    @TempDir Path temporary;

    @Test @EnabledOnOs({OS.MAC, OS.LINUX})
    void realBashRetainsBannerAndOnePromptAndPreservesPromptCommands() throws Exception {
        Path rc = temporary.resolve("bashrc");
        Files.writeString(rc, "printf 'LOGIN BANNER\\n'; PS1='PROMPT> '; PROMPT_COMMAND='printf existing-hook >&2;';\n");
        exercise(new String[]{"/bin/bash", "--noprofile", "--rcfile", rc.toString(), "-i"}, false);
    }

    @Test @EnabledOnOs(OS.MAC)
    void realZshRetainsMultilineColouredPromptAndBanner() throws Exception {
        Files.writeString(temporary.resolve(".zshrc"), "printf 'LOGIN BANNER\\n'; PROMPT=$'%F{green}line one%f\\nPROMPT> ';\n");
        exercise(new String[]{"/bin/zsh", "-i"}, true);
    }

    @Test @EnabledOnOs(OS.MAC)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "jlshell.p10k.source", matches = ".+")
    void realPowerlevel10kRetainsFullFirstPromptWithTransientAndRightPrompt() throws Exception {
        String source = Path.of(System.getProperty("jlshell.p10k.source"), "powerlevel10k.zsh-theme").toString();
        Files.writeString(temporary.resolve(".zshrc"), """
                printf 'LOGIN BANNER\\n'
                typeset -g POWERLEVEL9K_DISABLE_CONFIGURATION_WIZARD=true
                typeset -g POWERLEVEL9K_DISABLE_GITSTATUS=true
                typeset -g POWERLEVEL9K_INSTANT_PROMPT=off
                typeset -g POWERLEVEL9K_TRANSIENT_PROMPT=always
                typeset -ga POWERLEVEL9K_LEFT_PROMPT_ELEMENTS=(fixture newline prompt_char)
                typeset -ga POWERLEVEL9K_RIGHT_PROMPT_ELEMENTS=(right_fixture)
                prompt_fixture() { p10k segment -f 2 -t FIRST-LINE; }
                prompt_right_fixture() { p10k segment -f 3 -t RIGHT-FRAME; }
                source '""" + source + "'\n");
        for (int columns : new int[]{50, 120}) exercise(new String[]{"/bin/zsh", "-i"}, true, true, columns);
    }

    private void exercise(String[] command, boolean zsh) throws Exception { exercise(command, zsh, false); }

    private void exercise(String[] command, boolean zsh, boolean powerlevel) throws Exception {
        exercise(command, zsh, powerlevel, 50);
    }

    private void exercise(String[] command, boolean zsh, boolean powerlevel, int columns) throws Exception {
        var environment = new HashMap<>(System.getenv());
        environment.put("TERM", "xterm-256color"); environment.put("ZDOTDIR", temporary.toString());
        environment.put("HISTFILE", "/dev/null");
        var process = new PtyProcessBuilder().setCommand(command).setEnvironment(environment)
                .setInitialColumns(columns).setInitialRows(24).setConsole(false).start();
        var integration = new ShellDirectoryIntegration();
        try {
            // Send before reading the banner, as happens on an SSH shell channel.
            assertTrue(integration.command().length() < 1024, "Bootstrap must fit small canonical PTY buffers");
            process.getOutputStream().write(integration.command().getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
            var chunks = new java.util.concurrent.LinkedBlockingQueue<String>();
            Thread pump = new Thread(() -> {
                try { char[] chars = new char[31]; int count;
                    while ((count = reader.read(chars)) >= 0) chunks.add(new String(chars, 0, count));
                } catch (java.io.IOException ignored) {}
            });
            pump.setDaemon(true); pump.start();
            StringBuilder visible = new StringBuilder(), raw = new StringBuilder();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (integration.pending() && System.nanoTime() < deadline) {
                String chunk = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                raw.append(chunk); visible.append(integration.filter(chunk));
            }
            assertFalse(integration.pending(), () -> "No prompt completion: " + raw);
            // RPROMPT and zle-line-init redraws can follow the PS1 completion marker.
            while (true) {
                String extra = chunks.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (extra == null) break;
                raw.append(extra); visible.append(integration.filter(extra));
            }
            String text = visible.toString();
            assertTrue(text.contains("LOGIN BANNER"), text);
            if (powerlevel) {
                Files.writeString(Path.of("target/p10k-visible.txt"), text);
                Files.writeString(Path.of("target/p10k-raw.txt"), raw);
                String screen = render(text, columns);
                assertTrue(screen.contains("LOGIN BANNER"), screen);
                assertTrue(screen.contains("FIRST-LINE"), screen);
                assertTrue(screen.contains("RIGHT-FRAME"), screen);
                assertEquals(1, screen.split("FIRST-LINE", -1).length - 1, screen);
                assertFalse(screen.contains("__jlshell"), screen);
            } else {
                String screen = render(text, columns);
                assertTrue(screen.contains("LOGIN BANNER"), screen);
                assertEquals(1, screen.split("PROMPT>", -1).length - 1, screen);
                if (zsh) assertEquals(1, screen.split("line one", -1).length - 1, screen);
            }
            assertFalse(text.contains("eval"), text);
            assertFalse(text.contains("__jlshell"), text);
            assertTrue(raw.toString().contains("\033]7;file://"), raw.toString());
            Path destination = Files.createTempDirectory(temporary, "中文 path%+");
            process.getOutputStream().write(("cd '" + destination + "'\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            StringBuilder changed = new StringBuilder();
            deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!changed.toString().contains("path%25+") && System.nanoTime() < deadline) {
                String chunk = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) changed.append(chunk);
            }
            assertTrue(changed.toString().contains("中文%20path%25+"), changed.toString());
            assertFalse(changed.toString().contains("jlshell-ready"), "The completion marker is only present in the bootstrap prompt");
        } finally { process.destroyForcibly(); }
    }

    private static String render(String output, int columns) throws Exception {
        var style = new com.jediterm.terminal.model.StyleState();
        var buffer = new com.jediterm.terminal.model.TerminalTextBuffer(columns, 24, style);
        var display = (com.jediterm.terminal.TerminalDisplay) java.lang.reflect.Proxy.newProxyInstance(
                ShellDirectoryIntegrationTest.class.getClassLoader(), new Class[]{com.jediterm.terminal.TerminalDisplay.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        var terminal = new com.jediterm.terminal.model.JediTerminal(display, buffer, style);
        var emulator = new com.jediterm.terminal.emulator.JediEmulator(
                new com.jediterm.terminal.ArrayTerminalDataStream(output.toCharArray()), terminal);
        while (emulator.hasNext()) emulator.next();
        StringBuilder screen = new StringBuilder();
        for (int line = 0; line < buffer.getScreenLinesCount(); line++) screen.append(buffer.getLine(line).getText()).append('\n');
        return screen.toString();
    }

    @Test void discardsTransientLineFinishWithCursorMovementForHiddenWrappedInput() throws Exception {
        var integration = new ShellDirectoryIntegration();
        String command = integration.command();
        var match = java.util.regex.Pattern.compile("__jlshell_init_([a-f0-9]+)").matcher(command);
        assertTrue(match.find()); String token = match.group(1);
        String initial = "LOGIN BANNER\r\nFIRST-LINE   RIGHT-FRAME\r\n> \033[?2004h";
        String stream = initial + command + "\033[16A\r\033[JSHORT> "
                + command + "\033]777;jlshell-begin-" + token + "\007"
                + "\033]777;jlshell-redraw-" + token + "\007"
                + "hidden setup prompt\033]777;jlshell-ready-" + token + "\007";
        StringBuilder visible = new StringBuilder();
        for (int offset = 0; offset < stream.length(); offset += 7)
            visible.append(integration.filter(stream.substring(offset, Math.min(stream.length(), offset + 7))));
        String screen = render(visible.toString(), 120);
        assertTrue(screen.contains("LOGIN BANNER"), screen);
        assertTrue(screen.contains("FIRST-LINE   RIGHT-FRAME"), screen);
        assertFalse(screen.contains("SHORT>"), screen);
        assertFalse(screen.contains("hidden setup"), screen);
    }

    @Test void syntaxHighlightingInsideEveryTokenDoesNotLeakSetupOrRemoveBanner() {
        var integration = new ShellDirectoryIntegration();
        String command = integration.command();
        var match = java.util.regex.Pattern.compile("__jlshell_init_([a-f0-9]+)").matcher(command);
        assertTrue(match.find()); String token = match.group(1);
        String highlighted = command.chars().mapToObj(c -> "\033[32m" + (char) c + "\033[0m")
                .collect(java.util.stream.Collectors.joining());
        String banner = "Login notice\r\nmultiline prompt\r\n$ ";
        String stream = banner + highlighted + "\033]777;jlshell-begin-" + token + "\007"
                + "extra prompt\033]777;jlshell-ready-" + token + "\007";
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < stream.length(); i++) visible.append(integration.filter(stream.substring(i, i + 1)));
        assertTrue(visible.toString().contains(banner));
        assertFalse(visible.toString().contains("eval"));
        assertFalse(visible.toString().contains("__jlshell"));
        assertFalse(visible.toString().contains("extra prompt"));
    }

    @Test void timeoutReleasesLoginOutputWithoutWaitingForever() {
        var integration = new ShellDirectoryIntegration(Duration.ZERO);
        assertEquals("login password: ", integration.filter("login password: "));
        assertFalse(integration.pending());
        assertEquals("next output", integration.filter("next output"));
    }
}
