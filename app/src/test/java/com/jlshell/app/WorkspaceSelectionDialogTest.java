package com.jlshell.app;

import com.jlshell.ui.swing.WorkspaceSelectionDialog;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class WorkspaceSelectionDialogTest {
    @Test void selectsExplicitSessionsAndReturnsCancellationWithoutTargets() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var settings = mock(AppSettingsService.class);
            when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
            var registry = mock(ColorSchemeRegistry.class);
            when(registry.findByName(anyString())).thenReturn(Optional.empty());
            var themes = new ThemeService(settings, registry, executor);
            var i18n = new I18nService(Locale.SIMPLIFIED_CHINESE);
            var sessions = List.of(
                    new Session("select-first", "connection-first", "First", "host-a", 22, "alice", "CONNECTED", Presentation.TAB, true, List.of("term-a"), List.of(Ready.TERMINAL)),
                    new Session("select-second", "connection-second", "Second", "host-b", 22, "bob", "CONNECTED", Presentation.TAB, false, List.of("term-b"), List.of(Ready.TERMINAL)));
            try {
                var accepted = WorkspaceSelectionDialog.choose(new SelectionRequest("Select", "Choose servers", true, List.of()), sessions, i18n, themes);
                JDialog window = awaitWindow("workspace.chooseSessions");
                SwingExecutors.runOnEdtAsync(() -> {
                    ((JList<?>) named(window, "workspace.sessionChoices")).setSelectionInterval(0, 1);
                    ((JButton) named(window, "dialog.ok")).doClick();
                }).get(3, TimeUnit.SECONDS);
                assertEquals(List.of("select-first", "select-second"), accepted.get(3, TimeUnit.SECONDS).sessionIds());
                var cancelled = WorkspaceSelectionDialog.roles(new RoleRequest("Roles", "Choose roles", List.of(new Role("source", "Source", true)), List.of()), sessions, i18n, themes);
                JDialog roles = awaitWindow("workspace.chooseRoles");
                SwingExecutors.runOnEdtAsync(() -> ((JButton) named(roles, "dialog.cancel")).doClick()).get(3, TimeUnit.SECONDS);
                assertTrue(cancelled.get(3, TimeUnit.SECONDS).cancelled());
                assertTrue(cancelled.get().sessions().isEmpty());
            } finally {
                SwingExecutors.runOnEdtAsync(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog && window.isShowing()) window.dispose();
                }).get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static JDialog awaitWindow(String name) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var window = SwingExecutors.supplyOnEdtAsync(() -> java.util.Arrays.stream(Window.getWindows())
                    .filter(value -> value instanceof JDialog && name.equals(value.getName()) && value.isShowing())
                    .map(JDialog.class::cast).findFirst().orElse(null)).get(3, TimeUnit.SECONDS);
            if (window != null) return window;
            Thread.sleep(25);
        }
        throw new AssertionError("Dialog did not open: " + name);
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            var found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
