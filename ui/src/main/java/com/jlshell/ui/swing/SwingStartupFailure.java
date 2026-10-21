package com.jlshell.ui.swing;

import com.jlshell.ui.theme.AppTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 初始化服务不可用时仍能独立展示的原生错误窗口。 */
public final class SwingStartupFailure {
    private SwingStartupFailure() {}

    /** 在 EDT 创建，退出回调只执行一次；调用方负责清理与结束进程。 */
    public static JDialog create(Throwable failure, Runnable onExit) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EDT required");
        boolean zh = Locale.getDefault().getLanguage().equals("zh");
        SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
        JDialog dialog = new JDialog((Frame) null, zh ? "JLShell — 启动失败" : "JLShell — Startup failed", false);
        dialog.setName("startup.failure");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        SwingDialogChrome.configure(dialog);
        JPanel body = new JPanel(new BorderLayout(20, 18));
        body.setBorder(BorderFactory.createEmptyBorder(26, 28, 24, 28));
        body.add(new JLabel(new SwingBrandIcon(64)), BorderLayout.WEST);
        JPanel message = new JPanel();
        message.setLayout(new BoxLayout(message, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(zh ? "暂时无法启动 JLShell" : "JLShell couldn’t start");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.add(title);
        message.add(Box.createVerticalStrut(10));
        JTextArea hint = new JTextArea(zh ? "工作空间初始化未能完成。请查看下方错误信息，\n排除问题后重新打开应用。" : "Workspace initialization could not finish. Review the error\nbelow, resolve the issue, then reopen the application.");
        hint.setEditable(false); hint.setOpaque(false); hint.setFocusable(false); hint.setFont(UIManager.getFont("Label.font"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.add(hint);
        body.add(message, BorderLayout.CENTER);
        JPanel lower = new JPanel(new BorderLayout(0, 14));
        JTextArea summary = new JTextArea(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setFont(UIManager.getFont("Label.font"));
        summary.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        summary.setRows(3); summary.setName("startup.failure.summary");
        lower.add(new JScrollPane(summary), BorderLayout.NORTH);
        JPanel details = new JPanel(new BorderLayout(0, 10));
        JToggleButton toggle = new JToggleButton(zh ? "显示诊断详情" : "Show diagnostic details");
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        details.add(toggle, BorderLayout.NORTH);
        StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
        JTextArea text = new JTextArea(trace.toString());
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setCaretPosition(0); text.setName("startup.failure.details");
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(0, 190)); scroll.setVisible(false);
        details.add(scroll, BorderLayout.CENTER);
        toggle.addActionListener(event -> { scroll.setVisible(toggle.isSelected()); dialog.pack(); });
        lower.add(details, BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout(0, 22)); content.add(body, BorderLayout.NORTH);
        lower.setBorder(BorderFactory.createEmptyBorder(0, 28, 22, 28)); content.add(lower, BorderLayout.CENTER);
        dialog.add(content, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingTheme.color("border")), BorderFactory.createEmptyBorder(12, 20, 12, 20)));
        JButton exit = new JButton(zh ? "退出 JLShell" : "Quit JLShell"); exit.setName("startup.failure.exit");
        footer.add(exit); dialog.add(footer, BorderLayout.SOUTH); dialog.getRootPane().setDefaultButton(exit);
        AtomicBoolean exited = new AtomicBoolean();
        Runnable close = () -> { if (exited.compareAndSet(false, true)) { dialog.dispose(); onExit.run(); } };
        exit.addActionListener(event -> close.run());
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { close.run(); } });
        dialog.getRootPane().registerKeyboardAction(event -> close.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setMinimumSize(new Dimension(600, 0)); dialog.pack(); dialog.setLocationRelativeTo(null);
        return dialog;
    }
}
