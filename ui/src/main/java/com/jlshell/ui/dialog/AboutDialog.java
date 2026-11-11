package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.swing.SwingAboutPage;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.theme.ThemeService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared native product page for Help/About and preferences. */
public final class AboutDialog {
    private AboutDialog() {}
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, I18nService i18n, ThemeService themes) { return show(owner, i18n, themes, null); }
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, I18nService i18n, ThemeService themes, Runnable checkUpdates) {
        AtomicBoolean requested = new AtomicBoolean();
        return SwingFormDialog.showMessageAsync(i18n.get("menu.help.about"), "jlshell.about", i18n, themes, () -> {
            var page = SwingAboutPage.create(PreferencesDialog.getVersion(), i18n, PreferencesDialog::buildRuntimeDiagnostics, checkUpdates == null ? null : () -> {
                requested.set(true);
                for (java.awt.Window window : java.awt.Window.getWindows())
                    if (window instanceof javax.swing.JDialog && "jlshell.about".equals(window.getName()) && window.isShowing()) window.dispose();
            });
            page.setName("dialog.content");
            return page;
        }, 820, 760).thenRun(() -> { if (requested.get()) checkUpdates.run(); });
    }
}
