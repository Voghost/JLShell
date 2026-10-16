package com.jlshell.app;

import com.jlshell.ui.swing.SwingBrandIcon;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** 独立于服务初始化的 Swing 启动窗口；所有窗口状态由 EDT 持有。 */
public final class StartupSplash {
    private static final Color BACKGROUND = new Color(14, 24, 41);
    private final CompletableFuture<Void> firstFrame = new CompletableFuture<>();
    private JWindow window;
    private Timer animation;
    private boolean closed;
    private String status = zh() ? "正在准备工作空间" : "Preparing your workspace";

    public void show() {
        SwingUtilities.invokeLater(() -> {
            if (closed || window != null) return;
            try {
                window = new JWindow();
                window.setName("startup.splash");
                // 原生窗口在 Swing 首次 paint 前也使用品牌底色，避免白色 backing surface 闪现。
                window.setBackground(BACKGROUND);
                window.getRootPane().setBackground(BACKGROUND);
                window.setFocusableWindowState(false);
                // 冷启动时应用还没有活动主窗口，macOS 可能把非焦点 splash 放到终端/IDE 后面。
                // 仅启动阶段置顶，不抢输入焦点；主窗口就绪后立即释放。
                window.setAlwaysOnTop(true);
                window.setSize(520, 300);
                window.setLocationRelativeTo(null);
                window.setContentPane(new Artwork());
                if (window.getGraphicsConfiguration().getDevice()
                        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
                    window.setShape(new RoundRectangle2D.Double(0, 0, 520, 300, 24, 24));
                }
                animation = new Timer(32, event -> window.repaint());
                window.setVisible(true);
                animation.start();
            } catch (Throwable error) {
                if (animation != null) animation.stop();
                if (window != null) { window.dispose(); window = null; }
                firstFrame.completeExceptionally(error);
            }
        });
    }

    /** 只在 Launcher 主线程等待；不能从 FX/EDT 等待它。 */
    CompletableFuture<Void> firstFrame() { return firstFrame; }

    public void updateStatus(String chinese, String english) {
        SwingUtilities.invokeLater(() -> { status = zh() ? chinese : english; if (window != null) window.repaint(); });
    }

    public void hide() {
        SwingUtilities.invokeLater(() -> {
            closed = true;
            if (!firstFrame.isDone()) firstFrame.completeExceptionally(
                    new java.util.concurrent.CancellationException("Splash closed before first frame"));
            if (animation != null) animation.stop();
            if (window != null) { window.dispose(); window = null; }
        });
    }

    private static boolean zh() { return Locale.getDefault().getLanguage().equals("zh"); }

    private final class Artwork extends JPanel {
        private final long started = System.nanoTime();
        private final SwingBrandIcon icon = new SwingBrandIcon(80);
        Artwork() {
            setBackground(BACKGROUND);
            setPreferredSize(new Dimension(520, 300));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(25, 42, 66), 520, 300, new Color(14, 24, 41)));
                g.fillRect(0, 0, getWidth(), getHeight());
                // 安静的终端网格：只做背景秩序，不与品牌争夺注意力。
                g.setColor(new Color(142, 171, 201, 12));
                for (int x = 336; x < 520; x += 24) g.drawLine(x, 0, x, 214);
                for (int y = 22; y < 215; y += 24) g.drawLine(336, y, 520, y);
                icon.paintIcon(this, g, 34, 50);
                g.setColor(new Color(241, 246, 251));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 38));
                g.drawString("JLShell", 132, 91);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.setColor(new Color(150, 176, 201));
                g.drawString("SSH / SFTP CLIENT", 134, 117);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
                g.setColor(new Color(220, 230, 241));
                g.drawString(zh() ? "连接远程，自在掌控。" : "Your servers. Within reach.", 40, 183);
                g.setColor(new Color(123, 150, 178));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString(status + "…", 40, 244);
                String version = StartupSplash.class.getPackage().getImplementationVersion();
                String label = version == null ? "DEVELOPMENT" : "v" + version;
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                g.drawString(label, 480 - g.getFontMetrics().stringWidth(label), 244);
                g.setColor(new Color(45, 65, 87));
                g.fillRoundRect(40, 264, 440, 3, 3, 3);
                g.setClip(40, 264, 440, 3);
                int offset = (int) (((System.nanoTime() - started) / 1_000_000L % 2100) * 550 / 2100) - 110;
                g.setPaint(new GradientPaint(40 + offset, 0, new Color(65, 106, 159), 150 + offset, 0, new Color(104, 175, 191)));
                g.fillRoundRect(40 + offset, 264, 110, 3, 3, 3);
            } finally { g.dispose(); }
            if (!firstFrame.isDone() && isShowing()) {
                // 下一次 EDT 任务执行时，本轮窗口绘制已结束；提交到原生显示管线后再启动 FX。
                SwingUtilities.invokeLater(() -> {
                    Toolkit.getDefaultToolkit().sync();
                    firstFrame.complete(null);
                });
            }
        }
    }
}
