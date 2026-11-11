package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 统一消息与确认窗口，调用方通过异步结果继续业务流程。 */
public final class SwingMessages {
    private SwingMessages() {}
    private static JComponent content(String message) {
        JTextArea text = new JTextArea(message);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setOpaque(false); text.setFont(UIManager.getFont("Label.font"));
        text.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setName("dialog.messageScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(480, messageHeight(message)));
        return scroll;
    }
    private static int messageHeight(String message) {
        long lines = message.lines().mapToLong(line -> Math.max(1, (line.length() + 59) / 60)).sum();
        return (int) Math.max(60, Math.min(360, lines * 20 + 36));
    }
    public static CompletableFuture<Void> show(String title, String message, ThemeService themes, I18nService i18n) {
        return SwingFormDialog.showMessageAsync(title, "jlshell.message", i18n, themes, () -> content(message), 560, messageHeight(message) + 110);
    }
    public static CompletableFuture<Boolean> confirm(String title, String message, ThemeService themes, I18nService i18n) {
        return SwingFormDialog.showAsync(title, "jlshell.confirm", i18n, themes,
                () -> new SwingFormDialog.Form<>(content(message), () -> true), 560, 260).thenApply(Boolean.TRUE::equals);
    }
    public static CompletableFuture<String> choose(String title, String message, List<String> choices, ThemeService themes, I18nService i18n) {
        return SwingFormDialog.showAsync(title, "jlshell.choice", i18n, themes,
                () -> new SwingFormDialog.Form<>(content(message), () -> null, null,
                        choices.stream().map(text -> new SwingFormDialog.Choice<>(text, text)).toList()), 580, 280);
    }
}
