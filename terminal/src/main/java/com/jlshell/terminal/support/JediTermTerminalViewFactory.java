package com.jlshell.terminal.support;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.session.ShellChannel;
import com.jlshell.core.session.SshSession;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.service.TerminalViewHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JediTerm 的终端视图工厂。
 */
public class JediTermTerminalViewFactory implements TerminalViewFactory {

    private static final Logger log = LoggerFactory.getLogger(JediTermTerminalViewFactory.class);

    private final FontProfileService fontProfileService;
    private final ExecutorService executorService;
    private final Function<String, String> i18n;

    public JediTermTerminalViewFactory(FontProfileService fontProfileService, ExecutorService executorService,
                                       Function<String, String> i18n) {
        this.fontProfileService = fontProfileService;
        this.executorService = executorService;
        this.i18n = i18n != null ? i18n : key -> key;
    }

    @Override
    public CompletableFuture<TerminalViewHandle> createTerminalView(SshSession sshSession, TerminalViewRequest request) {
        Objects.requireNonNull(sshSession, "sshSession must not be null");
        TerminalViewRequest resolvedRequest = resolveRequest(request);

        return sshSession.openShell(resolvedRequest.shellRequest())
                .thenCompose(shellChannel -> createOnEdt(sshSession, resolvedRequest, shellChannel))
                .whenComplete((viewHandle, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to create terminal view for session {}", sshSession.sessionId(), throwable);
                    }
                });
    }

    private TerminalViewRequest resolveRequest(TerminalViewRequest request) {
        TerminalViewRequest baseRequest = request == null ? new TerminalViewRequest(null, null, null, null) : request;
        FontProfile fontProfile = baseRequest.fontProfile() == null
                ? fontProfileService.activeProfile()
                : baseRequest.fontProfile();
        return baseRequest.withResolvedFontProfile(fontProfile);
    }

    private CompletableFuture<TerminalViewHandle> createOnEdt(
            SshSession sshSession,
            TerminalViewRequest request,
            ShellChannel shellChannel
    ) {
        return SwingExecutors.supplyOnEdtAsync(() -> {
            try {
                JlshellSettingsProvider settingsProvider =
                        new JlshellSettingsProvider(request.fontProfile(), request.colorScheme());
                ShellTtyConnector ttyConnector =
                        new ShellTtyConnector(sshSession.displayName(), shellChannel, executorService);
                JlshellJediTermWidget widget = JlshellJediTermWidget.create(
                        request.shellRequest().terminalSize().columns(),
                        request.shellRequest().terminalSize().rows(),
                        settingsProvider,
                        i18n
                );
                widget.setTtyConnector(ttyConnector);
                widget.start();
                widget.refreshVisuals();

                return new DefaultTerminalViewHandle(
                        sshSession.sessionId(),
                        request.title(),
                        widget,
                        settingsProvider,
                        ttyConnector
                );
            } catch (Exception exception) {
                shellChannel.closeAsync();
                throw new CompletionException(exception);
            }
        });
    }
}
