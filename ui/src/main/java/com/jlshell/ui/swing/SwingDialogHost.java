package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogEvent;
import javafx.scene.layout.BorderPane;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicReference;

/** 仅替换关于/项目管理窗口的呈现；原 FX 表单、Dialog.close 和插件 Node 保持不变。 */
public final class SwingDialogHost {
    private SwingDialogHost() {}

    public static boolean enabled() {
        return !"javafx".equalsIgnoreCase(System.getProperty("jlshell.ui.renderer", "swing"));
    }

    public static void showAndWait(javafx.scene.control.Dialog<Void> model, ThemeService themes,
                                   I18nService i18n, String name, int width, int height) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Dialog model requires FX thread");
        Node node = model.getDialogPane().getContent();
        model.getDialogPane().setContent(null);
        Object loop = new Object();
        AtomicReference<JDialog> view = new AtomicReference<>();
        AtomicReference<SwingFxPanel> content = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        ChangeListener<Object> themeListener = (o, old, value) -> SwingUtilities.invokeLater(() -> {
            if (view.get() != null) SwingTheme.update(view.get());
        });
        EventHandler<DialogEvent> hidden = event -> SwingUtilities.invokeLater(() -> {
            if (view.get() != null) view.get().dispose();
        });
        javafx.collections.ListChangeListener<javafx.stage.Window> modalListener = change -> {
            boolean blocked = javafx.stage.Window.getWindows().stream().anyMatch(window ->
                    window instanceof javafx.stage.Stage stage && stage.isShowing()
                            && stage.getModality() != javafx.stage.Modality.NONE);
            SwingUtilities.invokeLater(() -> { if (view.get() != null) view.get().setEnabled(!blocked); });
        };
        themes.currentThemeProperty().addListener(themeListener);
        themes.accentColorProperty().addListener(themeListener);
        model.addEventHandler(DialogEvent.DIALOG_HIDDEN, hidden);
        javafx.stage.Window.getWindows().addListener(modalListener);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            themes.currentThemeProperty().removeListener(themeListener);
            themes.accentColorProperty().removeListener(themeListener);
            model.removeEventHandler(DialogEvent.DIALOG_HIDDEN, hidden);
            javafx.stage.Window.getWindows().removeListener(modalListener);
            if (node.getParent() instanceof BorderPane wrapper && wrapper.getCenter() == node) wrapper.setCenter(null);
            if (content.get() != null) content.get().close();
            model.getDialogPane().setContent(node);
            Platform.exitNestedEventLoop(loop, null);
        };
        SwingUtilities.invokeLater(() -> {
            Window owner = SwingDialogChrome.owner();
            boolean wasEnabled = owner != null && owner.isEnabled();
            Object oldModal = owner instanceof RootPaneContainer host
                    ? host.getRootPane().getClientProperty("jlshell.settingsModal") : null;
            try {
                JDialog dialog = new JDialog(owner, model.getTitle(), Dialog.ModalityType.MODELESS);
                dialog.setName(name);
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                view.set(dialog);
                SwingDialogChrome.configure(dialog);
                SwingFxPanel panel = new SwingFxPanel(themes);
                content.set(panel);
                panel.setName("dialog.content");
                panel.setOwnsDialogKeys(true);
                panel.setDialogView(node);
                dialog.add(panel, BorderLayout.CENTER);
                JButton close = new JButton(i18n.get("window.close"));
                close.setName("dialog.close");
                close.addActionListener(event -> FxThread.run(() ->
                        ((javafx.scene.control.Button) model.getDialogPane().lookupButton(ButtonType.CLOSE)).fire()));
                JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
                footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingTheme.color("border")));
                footer.add(close);
                dialog.add(footer, BorderLayout.SOUTH);
                Runnable requestClose = () -> FxThread.run(model::close);
                dialog.getRootPane().registerKeyboardAction(event -> requestClose.run(),
                        KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
                FxThread.run(() -> panel.getScene().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (!event.isConsumed() && event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        event.consume(); model.close();
                    }
                }));
                dialog.addWindowListener(new WindowAdapter() {
                    public void windowClosing(WindowEvent event) { requestClose.run(); }
                    public void windowClosed(WindowEvent event) {
                        if (owner instanceof RootPaneContainer host) host.getRootPane().putClientProperty("jlshell.settingsModal", oldModal);
                        if (owner != null) owner.setEnabled(wasEnabled);
                        FxThread.run(cleanup);
                    }
                });
                dialog.setMinimumSize(new Dimension(Math.min(width, 700), Math.min(height, 460)));
                Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
                dialog.setSize(Math.min(width, screen.width - 80), Math.min(height, screen.height - 80));
                dialog.setLocationRelativeTo(owner);
                if (owner instanceof RootPaneContainer host) host.getRootPane().putClientProperty("jlshell.settingsModal", true);
                if (owner != null) owner.setEnabled(false);
                dialog.setVisible(true);
            } catch (Throwable error) {
                failure.set(error);
                if (view.get() != null && view.get().isDisplayable()) view.get().dispose();
                else {
                    if (owner instanceof RootPaneContainer host) host.getRootPane().putClientProperty("jlshell.settingsModal", oldModal);
                    if (owner != null) owner.setEnabled(wasEnabled);
                    FxThread.run(cleanup);
                }
            }
        });
        Platform.enterNestedEventLoop(loop);
        if (failure.get() != null) throw new IllegalStateException("Cannot display " + name, failure.get());
    }
}
