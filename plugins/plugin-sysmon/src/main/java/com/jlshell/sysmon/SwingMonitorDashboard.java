package com.jlshell.sysmon;

import com.jlshell.plugin.api.PluginContext;
import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原生 Swing 监控页；采样不阻塞 EDT，隐藏/关闭后停止定时器并丢弃过期结果。 */
public final class SwingMonitorDashboard extends JPanel implements AutoCloseable {
    private final PluginContext context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sysmon-native-collector"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final javax.swing.Timer timer;
    private final JLabel title = new JLabel(), state = new JLabel("…");
    private final JToggleButton local = new JToggleButton(), remote = new JToggleButton();
    private final JPanel grid = new JPanel(new GridLayout(0, 1, 10, 10));
    private final Metric[] metrics = {new Metric(new Color(91, 150, 218)), new Metric(new Color(102, 176, 143)),
            new Metric(new Color(193, 157, 95)), new Metric(new Color(163, 138, 204))};
    private MetricsCollector localCollector;
    private RemoteMetricsCollector remoteCollector;
    private boolean remoteMode, closed;
    private long generation, previousRecv = -1, previousSent = -1, previousTime;
    private Locale locale;

    public SwingMonitorDashboard(PluginContext context) {
        super(new BorderLayout(0, 16)); this.context = context; setName("sysmon.swing.dashboard");
        setBorder(BorderFactory.createEmptyBorder(16, 14, 14, 14));
        remoteMode = context.sshSession().isPresent();
        JPanel header = new JPanel(new BorderLayout(0, 10));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f)); header.add(title, BorderLayout.NORTH);
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup group = new ButtonGroup(); group.add(local); group.add(remote);
        local.setSelected(!remoteMode); remote.setSelected(remoteMode); remote.setEnabled(remoteMode);
        modes.add(local); modes.add(Box.createHorizontalStrut(6)); modes.add(remote); header.add(modes, BorderLayout.CENTER);
        state.setFont(state.getFont().deriveFont(11f)); header.add(state, BorderLayout.SOUTH); add(header, BorderLayout.NORTH);
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() { return new Dimension(0, grid.getPreferredSize().height); }
        };
        for (Metric metric : metrics) grid.add(metric);
        wrapper.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrapper); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);
        timer = new javax.swing.Timer(2000, event -> poll());
        local.addActionListener(event -> switchSource(false)); remote.addActionListener(event -> switchSource(true));
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                int columns = getWidth() >= 540 ? 2 : 1;
                if (((GridLayout)grid.getLayout()).getColumns() != columns) {
                    grid.setLayout(new GridLayout(0, columns, 10, 10)); grid.revalidate();
                }
            }
        });
        addHierarchyListener(event -> { if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
            if (isShowing()) startPolling(); else stopPolling();
        }});
        applyLocale(context.locale());
    }

    public void applyLocale(Locale value) {
        SwingUtilities.invokeLater(() -> {
            if (closed) return; locale = value;
            boolean zh = value.getLanguage().equals("zh");
            title.setText(zh ? "系统监控" : "System Monitor");
            local.setText(zh ? "本机" : "Local"); remote.setText(zh ? "远程" : "Remote");
            String[] names = zh ? new String[]{"CPU", "内存", "网络", "存储"} : new String[]{"CPU", "Memory", "Network", "Storage"};
            for (int i = 0; i < metrics.length; i++) metrics[i].title = names[i];
            updateSource(); repaint();
        });
    }
    public void applyTheme(String ignored) { SwingUtilities.invokeLater(this::repaint); }
    public void startPolling() { onEdt(() -> { if (!closed && !timer.isRunning()) { timer.start(); poll(); } }); }
    public void stopPolling() { onEdt(() -> { timer.stop(); generation++; }); }
    private void updateSource() {
        boolean zh = locale != null && locale.getLanguage().equals("zh");
        state.setText(remoteMode ? (zh ? "远程会话 · 每 2 秒更新" : "Remote session · Every 2 seconds")
                : (zh ? "本机 · 每 2 秒更新" : "Local machine · Every 2 seconds"));
    }
    private void switchSource(boolean value) {
        remoteMode = value; generation++; previousRecv = previousSent = -1; previousTime = 0;
        for (Metric metric : metrics) { metric.values.clear(); metric.value = "—"; metric.detail = ""; }
        updateSource(); repaint(); poll();
    }
    private void poll() {
        if (closed || !busy.compareAndSet(false, true)) return;
        long request = generation; boolean useRemote = remoteMode;
        CompletableFuture.supplyAsync(() -> {
            if (useRemote) {
                if (remoteCollector == null) remoteCollector = new RemoteMetricsCollector(context.sshSession().orElseThrow());
                return remoteCollector.collect().join();
            }
            if (localCollector == null) localCollector = new MetricsCollector();
            return localCollector.collect();
        }, worker).whenComplete((snapshot, error) -> SwingUtilities.invokeLater(() -> {
            busy.set(false); if (closed || request != generation) return;
            if (error != null) { state.setText((locale.getLanguage().equals("zh") ? "采样失败，稍后重试" : "Collection failed; retrying")); return; }
            updateSource(); render(snapshot, useRemote);
        }));
    }
    private void render(SystemMetrics m, boolean remoteSample) {
        metrics[0].set(String.format(Locale.ROOT, "%.1f%%", m.cpuUsage()), m.cpuCores() + " cores · " + String.format(Locale.ROOT, "Load %.2f", m.cpuLoadAvg1m()), m.cpuUsage());
        metrics[1].set(String.format(Locale.ROOT, "%.1f%%", m.memUsagePercent()), bytes(m.memUsed()) + " / " + bytes(m.memTotal()), m.memUsagePercent());
        double seconds = previousTime > 0 ? Math.max(.001, (m.timestamp() - previousTime) / 1000d) : 2;
        long recv = remoteSample ? (previousRecv < 0 ? 0 : Math.max(0, m.netBytesRecv() - previousRecv)) : m.netBytesRecv();
        long sent = remoteSample ? (previousSent < 0 ? 0 : Math.max(0, m.netBytesSent() - previousSent)) : m.netBytesSent();
        previousRecv = m.netBytesRecv(); previousSent = m.netBytesSent(); previousTime = m.timestamp();
        metrics[2].set("↓ " + bytes((long)(recv / seconds)) + "/s", "↑ " + bytes((long)(sent / seconds)) + "/s", (recv + sent) / seconds);
        if (!m.disks().isEmpty()) {
            var disk = m.disks().getFirst(); metrics[3].set(String.format(Locale.ROOT, "%.1f%%", disk.usagePercent()), disk.mount() + " · " + bytes(disk.used()) + " / " + bytes(disk.total()), disk.usagePercent());
        }
    }
    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        int exponent = Math.min(4, (int)(Math.log(value) / Math.log(1024)));
        return String.format(Locale.ROOT, "%.1f %s", value / Math.pow(1024, exponent), new String[]{"B", "KB", "MB", "GB", "TB"}[exponent]);
    }
    private static void onEdt(Runnable action) { if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action); }
    @Override public void close() { onEdt(() -> { if (closed) return; closed = true; generation++; timer.stop(); worker.shutdownNow(); }); }

    private static final class Metric extends JPanel {
        private final Color accent;
        private String title = "", value = "—", detail = "";
        private final List<Double> values = new ArrayList<>();
        Metric(Color accent) { this.accent = accent; setOpaque(false); setPreferredSize(new Dimension(0, 154)); }
        void set(String value, String detail, double sample) {
            this.value = value; this.detail = detail; values.add(Double.isFinite(sample) ? sample : 0);
            if (values.size() > 90) values.removeFirst(); setToolTipText(detail); repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(UIManager.getColor("TextField.background")); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                Color fg = UIManager.getColor("Label.foreground"), secondary = UIManager.getColor("Label.disabledForeground");
                Font base = UIManager.getFont("Label.font");
                g.setFont(base); g.setColor(secondary); g.drawString(title, 14, 25);
                g.setColor(fg); g.setFont(base.deriveFont(Font.BOLD, 23f)); g.drawString(value, 14, 59);
                g.setFont(base.deriveFont(11f)); g.setColor(secondary);
                String text = detail; while (!text.isEmpty() && g.getFontMetrics().stringWidth(text) > getWidth() - 28) text = text.substring(0, text.length() - 1);
                g.drawString(text, 14, 81);
                int top = 100, bottom = getHeight() - 14; double max = Math.max(100, values.stream().mapToDouble(Double::doubleValue).max().orElse(100));
                g.setColor(accent); g.setStroke(new BasicStroke(1.5f));
                for (int i = 1; i < values.size(); i++) {
                    int x1 = 14 + (getWidth() - 28) * (i - 1) / 89, x2 = 14 + (getWidth() - 28) * i / 89;
                    g.drawLine(x1, bottom - (int)((bottom - top) * values.get(i - 1) / max), x2, bottom - (int)((bottom - top) * values.get(i) / max));
                }
            } finally { g.dispose(); }
        }
    }
}
