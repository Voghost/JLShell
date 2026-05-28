package com.jlshell.ui.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.JlshellJediTermWidget;
import com.jlshell.terminal.support.JlshellSettingsProvider;
import com.jlshell.terminal.support.LocalShellTerminalViewHandle;
import com.jlshell.terminal.support.LocalShellTtyConnector;
import com.jlshell.terminal.support.SwingExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;

/**
 * 启动本地 Shell 终端。
 */
public class LocalShellLauncher {

    private static final Logger log = LoggerFactory.getLogger(LocalShellLauncher.class);

    private final FontProfileService fontProfileService;
    private final ExecutorService executorService;
    private final Function<String, String> i18n;

    public LocalShellLauncher(
            FontProfileService fontProfileService,
            ExecutorService executorService,
            I18nService i18nService
    ) {
        this.fontProfileService = fontProfileService;
        this.executorService = executorService;
        this.i18n = i18nService::get;
    }

    public CompletableFuture<TerminalViewHandle> launch(String displayName, TerminalViewRequest request) {
        TerminalViewRequest resolved = resolveRequest(request);
        String[] command = detectShell();
        Charset charset = isWindows() ? detectWindowsCharset() : null;
        log.info("Launching local shell for '{}': {}", displayName, java.util.Arrays.toString(command));
        return createOnEdt(displayName, resolved, command, charset);
    }

    private String[] detectShell() {
        String shellEnv = System.getenv("SHELL");
        if (shellEnv != null && !shellEnv.isBlank()) {
            return new String[]{shellEnv, "-l"};
        }
        if (isWindows()) {
            return new String[]{"cmd.exe"};
        }
        for (String shell : List.of("/bin/zsh", "/bin/bash", "/bin/sh")) {
            if (new java.io.File(shell).exists()) {
                return new String[]{shell, "-l"};
            }
        }
        return new String[]{"/bin/sh"};
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Detect the Windows console codepage and map it to a Java Charset. */
    private static Charset detectWindowsCharset() {
        try {
            Process pb = new ProcessBuilder("chcp.com").redirectErrorStream(true).start();
            String output = new String(pb.getInputStream().readAllBytes());
            pb.waitFor();
            // Output like "Active code page: 936"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(output);
            if (m.find()) {
                int cp = Integer.parseInt(m.group(1));
                Charset cs = codepageToCharset(cp);
                log.info("Windows codepage {} → charset {}", cp, cs);
                return cs;
            }
        } catch (Exception e) {
            log.warn("Failed to detect Windows codepage, falling back to GBK", e);
        }
        return Charset.forName("GBK");
    }

    private static Charset codepageToCharset(int codepage) {
        switch (codepage) {
            case 936:  return Charset.forName("GBK");       // Chinese simplified
            case 950:  return Charset.forName("Big5");      // Chinese traditional
            case 65001: return java.nio.charset.StandardCharsets.UTF_8;
            case 437: case 850: case 1252:
                return java.nio.charset.StandardCharsets.ISO_8859_1;
            case 1251: return Charset.forName("windows-1251"); // Russian
            case 1250: return Charset.forName("windows-1250"); // Central European
            default:
                try { return Charset.forName("windows-" + codepage); }
                catch (Exception e) { return Charset.forName("GBK"); }
        }
    }

    private CompletableFuture<TerminalViewHandle> createOnEdt(
            String displayName, TerminalViewRequest request, String[] command, Charset charset) {
        return SwingExecutors.supplyOnEdtAsync(() -> {
            try {
                int cols = request.shellRequest().terminalSize().columns();
                int rows = request.shellRequest().terminalSize().rows();
                JlshellSettingsProvider settingsProvider =
                        new JlshellSettingsProvider(request.fontProfile(), request.colorScheme());
                LocalShellTtyConnector ttyConnector =
                        new LocalShellTtyConnector(displayName, command, cols, rows, executorService, charset);
                JlshellJediTermWidget widget = JlshellJediTermWidget.create(
                        cols, rows, settingsProvider, i18n);
                widget.setTtyConnector(ttyConnector);
                widget.start();
                widget.refreshVisuals();
                return (TerminalViewHandle) new LocalShellTerminalViewHandle(
                        SessionId.randomId(), request.title(), widget, settingsProvider, ttyConnector);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private TerminalViewRequest resolveRequest(TerminalViewRequest request) {
        TerminalViewRequest base = request == null ? new TerminalViewRequest(null, null, null, null) : request;
        FontProfile fontProfile = base.fontProfile() == null ? fontProfileService.activeProfile() : base.fontProfile();
        return base.withResolvedFontProfile(fontProfile);
    }
}
