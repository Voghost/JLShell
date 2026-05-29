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
import java.nio.charset.StandardCharsets;

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
        Charset charset = isWindows() ? StandardCharsets.UTF_8 : null;
        log.info("Launching local shell for '{}': {} with charset={}", displayName, java.util.Arrays.toString(command), charset);
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
