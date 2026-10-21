package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.support.FxThread;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 非阻塞的原生更新进度；关闭窗口仅转到后台，不伪装成已取消下载。 */
public final class SwingProgressDialog implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile double progress = -1;
    private JDialog dialog;
    private JProgressBar bar;
    private JLabel percentage;
    private final ThemeService themes;
    private final javafx.beans.value.ChangeListener<Object> themeListener;

    /** 在 FX 线程读取服务状态，Swing 窗口完全由 EDT 创建。 */
    public SwingProgressDialog(ThemeService themes, I18nService i18n, String name, String message) {
        this.themes = themes;
        var theme = themes.currentTheme(); String accent = themes.accentColor().color();
        String family = themes.uiFontFamily(); int size = themes.uiFontSize();
        String title = i18n.get("updates.title"), background = i18n.get("updates.background");
        themeListener = (obs, old, next) -> SwingUtilities.invokeLater(() -> {
            if (!closed.get() && dialog != null) SwingTheme.update(dialog);
        });
        themes.currentThemeProperty().addListener(themeListener);
        themes.accentColorProperty().addListener(themeListener);
        SwingUtilities.invokeLater(() -> {
            if (closed.get()) return;
            SwingTheme.install(theme, accent, family, size);
            dialog = new JDialog(SwingDialogChrome.owner(), title, Dialog.ModalityType.MODELESS);
            dialog.setName(name); dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            SwingDialogChrome.configure(dialog);
            JPanel body = new JPanel(new BorderLayout(16, 18));
            body.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            body.add(new JLabel(new SwingBrandIcon(48)), BorderLayout.WEST);
            JPanel content = new JPanel(new BorderLayout(0, 14));
            JLabel status = new JLabel(message); status.setName("progress.status");
            status.putClientProperty("html.disable", true);
            content.add(status, BorderLayout.NORTH);
            JPanel row = new JPanel(new BorderLayout(12, 0));
            bar = new JProgressBar(0, 1000); bar.setName("progress.bar");
            percentage = new JLabel("…", SwingConstants.RIGHT); percentage.setName("progress.percentage");
            percentage.setPreferredSize(new Dimension(42, 22));
            row.add(bar, BorderLayout.CENTER); row.add(percentage, BorderLayout.EAST);
            content.add(row, BorderLayout.CENTER); body.add(content, BorderLayout.CENTER);
            dialog.add(body, BorderLayout.CENTER);
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            footer.setBorder(BorderFactory.createCompoundBorder(SwingTheme.border(1, 0, 0, 0), BorderFactory.createEmptyBorder(10, 20, 10, 20)));
            JButton hide = new JButton(background); hide.setName("progress.background");
            hide.addActionListener(event -> close()); footer.add(hide); dialog.add(footer, BorderLayout.SOUTH);
            dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { close(); } });
            dialog.getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.setSize(480, 190); dialog.setLocationRelativeTo(dialog.getOwner());
            updateView(); dialog.setVisible(true);
        });
    }

    public void update(double value) {
        progress = Double.isFinite(value) && value >= 0 ? Math.min(1, value) : -1;
        if (!closed.get() && queued.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> {
            queued.set(false); if (!closed.get()) updateView();
        });
    }

    private void updateView() {
        if (bar == null) return;
        double value = progress;
        bar.setIndeterminate(value < 0); bar.setValue((int) Math.round(Math.max(0, value) * 1000));
        percentage.setText(value < 0 ? "…" : Math.round(value * 100) + "%");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> { if (dialog != null) dialog.dispose(); });
        FxThread.run(() -> {
            themes.currentThemeProperty().removeListener(themeListener);
            themes.accentColorProperty().removeListener(themeListener);
        });
    }
}
