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
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

/** 仅替换对话框呈现；原 FX 按钮校验、结果转换、Dialog.close 和插件 Node 保持不变。 */
public final class SwingDialogHost {
    private record ButtonSpec(ButtonType type, javafx.scene.control.Button source, String text, boolean disabled) {}
    private SwingDialogHost() {}

    public static boolean enabled() {
        return !"javafx".equalsIgnoreCase(System.getProperty("jlshell.ui.renderer", "swing"));
    }

    public static <R> Optional<R> showAndWait(javafx.scene.control.Dialog<R> model, ThemeService themes,
                                   I18nService i18n, String name, int width, int height) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Dialog model requires FX thread");
        Node node = model.getDialogPane().getContent();
        model.getDialogPane().setContent(null);
        List<ButtonSpec> buttons = model.getDialogPane().getButtonTypes().stream().map(type -> {
            var source = (javafx.scene.control.Button) model.getDialogPane().lookupButton(type);
            String text = type == ButtonType.OK ? i18n.get("settings.ok")
                    : type == ButtonType.CANCEL ? i18n.get("settings.cancel")
                    : type == ButtonType.CLOSE ? i18n.get("window.close") : source.getText();
            return new ButtonSpec(type, source, text, source.isDisabled());
        }).sorted(java.util.Comparator.comparing(spec -> spec.type().getButtonData().isDefaultButton())).toList();
        var nativeButtons = new HashMap<ButtonType, JButton>();
        List<Runnable> removeButtonListeners = new ArrayList<>();
        for (ButtonSpec spec : buttons) {
            ChangeListener<Boolean> listener = (o, old, disabled) -> SwingUtilities.invokeLater(() -> {
                JButton button = nativeButtons.get(spec.type());
                if (button != null) button.setEnabled(!disabled);
            });
            spec.source().disabledProperty().addListener(listener);
            removeButtonListeners.add(() -> spec.source().disabledProperty().removeListener(listener));
        }
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
            removeButtonListeners.forEach(Runnable::run);
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
                JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
                footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingTheme.color("border")));
                for (ButtonSpec spec : buttons) {
                    JButton button = new JButton(spec.text());
                    button.setName(spec.type() == ButtonType.CLOSE ? "dialog.close"
                            : spec.type().getButtonData().isDefaultButton() ? "dialog.ok" : "dialog.cancel");
                    button.setEnabled(!spec.disabled());
                    button.addActionListener(event -> FxThread.run(spec.source()::fire));
                    nativeButtons.put(spec.type(), button);
                    footer.add(button);
                    if (spec.type().getButtonData().isDefaultButton()) {
                        button.putClientProperty("JButton.buttonType", "default");
                        dialog.getRootPane().setDefaultButton(button);
                    }
                }
                dialog.add(footer, BorderLayout.SOUTH);
                Runnable requestClose = () -> FxThread.run(model::close);
                dialog.getRootPane().registerKeyboardAction(event -> requestClose.run(),
                        KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
                FxThread.run(() -> {
                    buttons.stream().filter(spec -> spec.type().getButtonData().isDefaultButton()).findFirst()
                            .ifPresent(spec -> panel.getScene().getAccelerators().put(
                                    new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.ENTER), spec.source()::fire));
                    panel.getScene().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (!event.isConsumed() && event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        event.consume(); model.close();
                    }
                    });
                });
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
        return Optional.ofNullable(model.getResult());
    }
}
