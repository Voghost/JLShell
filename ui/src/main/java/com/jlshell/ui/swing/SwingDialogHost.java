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
        return showAndWait(model, themes, i18n, name, width, height, false, false);
    }

    public static <R> Optional<R> showNativeAndWait(javafx.scene.control.Dialog<R> model, ThemeService themes,
                                      I18nService i18n, String name, int width, int height) {
        return showAndWait(model, themes, i18n, name, width, height, true, false);
    }

    /** Message dialogs grow with their wrapped text, with scrolling only at the screen limit. */
    public static <R> Optional<R> showNativeMessageAndWait(javafx.scene.control.Dialog<R> model, ThemeService themes,
                                      I18nService i18n, String name, int width) {
        return showAndWait(model, themes, i18n, name, width, 0, true, true);
    }

    private static <R> Optional<R> showAndWait(javafx.scene.control.Dialog<R> model, ThemeService themes,
                                   I18nService i18n, String name, int width, int height, boolean nativeForm, boolean fitContent) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Dialog model requires FX thread");
        Node node = model.getDialogPane().getContent();
        SwingFormRenderer renderer = nativeForm ? new SwingFormRenderer(i18n) : null;
        java.util.function.Supplier<JComponent> nativeContent = nativeForm ? renderer.prepare(node) : null;
        var theme = themes.currentTheme();
        String accent = themes.accentColor().color(), family = themes.uiFontFamily();
        int fontSize = themes.uiFontSize();
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
            if (renderer != null) renderer.close();
            model.getDialogPane().setContent(node);
            Platform.exitNestedEventLoop(loop, null);
        };
        SwingUtilities.invokeLater(() -> {
            Window owner = SwingDialogChrome.owner();
            try {
                SwingTheme.install(theme, accent, family, fontSize);
                JDialog dialog = new SwingModalDialog(owner, model.getTitle());
                dialog.setName(name);
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                view.set(dialog);
                SwingDialogChrome.configure(dialog);
                SwingFxPanel panel = nativeForm ? null : new SwingFxPanel(themes);
                JComponent body;
                if (nativeForm) {
                    body = nativeContent.get();
                    body.putClientProperty("jlshell.formModel", node);
                    body.putClientProperty("jlshell.formFields", renderer.models());
                } else {
                    content.set(panel);
                    panel.setOwnsDialogKeys(true);
                    panel.setDialogView(node);
                    body = panel;
                }
                body.setName("dialog.content");
                JScrollPane messageScroll = fitContent ? new JScrollPane(new MessageBody(body)) : null;
                if (messageScroll != null) {
                    messageScroll.setName("dialog.messageScroll");
                    messageScroll.setBorder(BorderFactory.createEmptyBorder());
                    messageScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                    messageScroll.getVerticalScrollBar().setUnitIncrement(16);
                }
                dialog.add(messageScroll == null ? body : messageScroll, BorderLayout.CENTER);
                JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
                footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingTheme.color("border")));
                for (ButtonSpec spec : buttons) {
                    JButton button = new JButton(spec.text());
                    Object explicitName = spec.source().getProperties().get("swing.name");
                    button.setName(explicitName instanceof String id ? id : spec.type() == ButtonType.CLOSE ? "dialog.close"
                            : spec.type().getButtonData().isDefaultButton() ? "dialog.ok"
                            : spec.type().getButtonData().isCancelButton() ? "dialog.cancel"
                            : "dialog.action." + buttons.indexOf(spec));
                    button.setEnabled(!spec.disabled());
                    button.addActionListener(event -> FxThread.run(spec.source()::fire));
                    nativeButtons.put(spec.type(), button);
                    button.putClientProperty("jlshell.primary", spec.type().getButtonData().isDefaultButton());
                    if (nativeForm) IdeaFormStyle.apply(button);
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
                if (panel != null) FxThread.run(() -> {
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
                        FxThread.run(cleanup);
                    }
                });
                Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
                Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
                int availableHeight = screen.height - screenInsets.top - screenInsets.bottom - 80;
                int targetWidth = Math.min(width, screen.width - screenInsets.left - screenInsets.right - 80);
                if (fitContent) {
                    dialog.setSize(targetWidth, availableHeight);
                    dialog.validate();
                    measureWrappedContent(body);
                    messageScroll.setPreferredSize(new Dimension(0, body.getPreferredSize().height));
                    dialog.pack();
                    int naturalHeight = dialog.getHeight();
                    dialog.setMinimumSize(new Dimension(Math.min(targetWidth, 360), Math.min(naturalHeight, 140)));
                    dialog.setSize(targetWidth, Math.min(naturalHeight, availableHeight));
                    dialog.validate();
                    // A scrollbar takes some width; measure wrapping once more at the final viewport width.
                    measureWrappedContent(body);
                    messageScroll.getViewport().getView().revalidate();
                } else {
                    dialog.setMinimumSize(new Dimension(Math.min(width, 560), Math.min(height, 280)));
                    dialog.setSize(targetWidth, Math.min(height, availableHeight));
                }
                dialog.setLocationRelativeTo(owner);
                dialog.setVisible(true);
            } catch (Throwable error) {
                failure.set(error);
                if (view.get() != null && view.get().isDisplayable()) view.get().dispose();
                else {
                    FxThread.run(cleanup);
                }
            }
        });
        Platform.enterNestedEventLoop(loop);
        if (failure.get() != null) throw new IllegalStateException("Cannot display " + name, failure.get());
        return Optional.ofNullable(model.getResult());
    }
    private static void measureWrappedContent(Component component) {
        if (component instanceof javax.swing.JTextArea text && !text.isEditable() && text.getLineWrap()) {
            text.setRows(0);
            text.setPreferredSize(null);
            Dimension preferred = text.getPreferredSize();
            text.setPreferredSize(new Dimension(Math.max(1, text.getWidth()), preferred.height));
        } else if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) measureWrappedContent(child);
            if (parent.getLayout() instanceof LayoutManager2 layout) layout.invalidateLayout(parent);
        }
    }

    private static final class MessageBody extends JPanel implements Scrollable {
        MessageBody(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(16, visible.height - 16); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

}
