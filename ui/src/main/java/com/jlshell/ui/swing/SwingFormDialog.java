package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Native form ownership and result delivery. Factories and submissions run on EDT. */
public final class SwingFormDialog {
    private SwingFormDialog() {}

    /** A null submission keeps the form open for validation corrections. */
    public record Choice<T>(String text, T value) {}
    public record Form<T>(JComponent content, Supplier<T> submit, String submitText, java.util.List<Choice<T>> choices) {
        public Form(JComponent content, Supplier<T> submit) { this(content, submit, null, java.util.List.of()); }
        public Form(JComponent content, Supplier<T> submit, String submitText) { this(content, submit, submitText, java.util.List.of()); }
        public Form { Objects.requireNonNull(content); Objects.requireNonNull(submit); }
    }

    public static <T> CompletableFuture<T> showAsync(String title, String name, I18nService i18n,
                                                    Supplier<Form<T>> factory, int width, int height) {
        return open(title, name, i18n, factory, width, height, false);
    }

    public static <T> CompletableFuture<T> showAsync(String title, String name, I18nService i18n,
            com.jlshell.ui.theme.ThemeService themes, Supplier<Form<T>> factory, int width, int height) {
        return showAsync(title, name, i18n, () -> {
            SwingTheme.install(themes);
            return factory.get();
        }, width, height);
    }

    public static CompletableFuture<Void> showMessageAsync(String title, String name, I18nService i18n,
            com.jlshell.ui.theme.ThemeService themes, Supplier<JComponent> factory, int width, int height) {
        return showMessageAsync(title, name, i18n, () -> {
            SwingTheme.install(themes);
            return factory.get();
        }, width, height);
    }

    public static <T> T showAndWait(String title, String name, I18nService i18n,
                                    Supplier<Form<T>> factory, int width, int height) {
        return await(showAsync(title, name, i18n, factory, width, height));
    }

    public static void showMessage(String title, String name, I18nService i18n,
                                   Supplier<JComponent> content, int width, int height) {
        await(open(title, name, i18n, () -> new Form<>(content.get(), () -> Boolean.TRUE), width, height, true));
    }

    public static CompletableFuture<Void> showMessageAsync(String title, String name, I18nService i18n,
                                                           Supplier<JComponent> content, int width, int height) {
        return open(title, name, i18n, () -> new Form<>(content.get(), () -> Boolean.TRUE), width, height, true)
                .thenApply(ignored -> null);
    }

    private static <T> CompletableFuture<T> open(String title, String name, I18nService i18n,
                                                Supplier<Form<T>> factory, int width, int height, boolean closeOnly) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<JDialog> window = new AtomicReference<>();
        result.whenComplete((value, error) -> {
            if (error != null) SwingUtilities.invokeLater(() -> {
                JDialog dialog = window.get();
                if (dialog != null) dialog.dispose();
            });
        });
        Runnable show = () -> {
            if (result.isDone()) return;
            try {
                Form<T> form = factory.get();
                JDialog dialog = new SwingModalDialog(SwingDialogChrome.owner(), title);
                window.set(dialog);
                dialog.setName(name);
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                SwingDialogChrome.configure(dialog);
                IdeaFormStyle.applyTree(form.content());
                dialog.add(form.content(), BorderLayout.CENTER);
                JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
                footer.setBorder(SwingTheme.border(1, 0, 0, 0));
                JButton cancel = new JButton(i18n.get("dialog.cancel"));
                cancel.setName("dialog.cancel");
                JButton ok = new JButton(form.submitText() == null ? i18n.get(closeOnly ? "dialog.close" : "dialog.ok") : form.submitText());
                ok.setName(closeOnly ? "dialog.close" : "dialog.ok");
                ok.putClientProperty("JLShell.primaryAction", true);
                if (!form.choices().isEmpty()) {
                    for (int index = 0; index < form.choices().size(); index++) {
                        Choice<T> choice = form.choices().get(index);
                        JButton button = new JButton(choice.text()); button.setName("dialog.choice." + index);
                        button.addActionListener(event -> { dialog.dispose(); result.complete(choice.value()); });
                        footer.add(button);
                    }
                }
                if (!closeOnly) footer.add(cancel);
                if (form.choices().isEmpty()) footer.add(ok);
                dialog.add(footer, BorderLayout.SOUTH);
                Runnable close = () -> { dialog.dispose(); result.complete(null); };
                cancel.addActionListener(event -> close.run());
                ok.addActionListener(event -> {
                    T accepted = form.submit().get();
                    if (accepted != null) { dialog.dispose(); result.complete(accepted); }
                });
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { close.run(); }
                    @Override public void windowClosed(WindowEvent event) {
                        if (form.content() instanceof AutoCloseable resource) {
                            try { resource.close(); }
                            catch (Exception error) { org.slf4j.LoggerFactory.getLogger(SwingFormDialog.class).warn("Cannot close form", error); }
                        }
                        result.complete(null);
                    }
                });
                dialog.getRootPane().setDefaultButton(form.choices().isEmpty() ? ok : (JButton) footer.getComponent(0));
                dialog.getRootPane().registerKeyboardAction(event -> close.run(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
                Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
                dialog.setSize(Math.min(width, screen.width - 40), Math.min(height, screen.height - 60));
                dialog.setLocationRelativeTo(dialog.getOwner());
                if (result.isDone()) dialog.dispose(); else dialog.setVisible(true);
            } catch (RuntimeException | Error error) { result.completeExceptionally(error); }
        };
        if (SwingUtilities.isEventDispatchThread()) show.run(); else SwingUtilities.invokeLater(show);
        return result;
    }

    public static <T> T await(CompletableFuture<T> result) {
        if (SwingUtilities.isEventDispatchThread() && !result.isDone()) {
            SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            result.whenComplete((value, error) -> SwingUtilities.invokeLater(loop::exit));
            loop.enter();
        }
        try { return result.get(); }
        catch (InterruptedException error) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while showing form", error);
        } catch (ExecutionException error) { throw new IllegalStateException("Cannot show form", error.getCause()); }
    }
}
