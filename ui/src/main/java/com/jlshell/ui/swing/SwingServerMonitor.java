package com.jlshell.ui.swing;

import com.jlshell.ui.model.ServerMonitorSnapshot;
import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Native tool-window content. Sampling remains in the session controller; no FX surface is created. */
final class SwingServerMonitor extends JPanel implements AutoCloseable {
    private final I18nService i18n;
    private final JLabel host = new JLabel(), state = new JLabel();
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final Function<String, CompletableFuture<String>> query;
    private final JPopupMenu details = new JPopupMenu();
    private final JLabel detailTitle = new JLabel();
    private final JTextArea detailText = new JTextArea(6, 58);
    private final javax.swing.table.DefaultTableModel processRows = new javax.swing.table.DefaultTableModel() {
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable processTable = new JTable(processRows);
    private final JScrollPane processScroll = new JScrollPane(processTable);
    private final Timer detailTimer;
    private String detailType;
    private long sample = -1, request;
    private boolean closed, inFlight;
    private boolean disconnected;
    private int warningThreshold = 75, dangerThreshold = 90;
    private Runnable unbind = () -> {};

    /** Snapshot FX properties on their owner thread and deliver immutable values to EDT. */
    static java.util.function.Supplier<SwingServerMonitor> prepare(com.jlshell.ui.view.TerminalWorkspaceView source, I18nService i18n) {
        int warning = source.monitorWarningThreshold(), danger = source.monitorDangerThreshold();
        var latest = new java.util.concurrent.atomic.AtomicReference<>(source.monitorSnapshotProperty().get());
        var view = new java.util.concurrent.atomic.AtomicReference<SwingServerMonitor>();
        javafx.beans.value.ChangeListener<ServerMonitorSnapshot> listener = (o, old, next) -> {
            latest.set(next);
            SwingUtilities.invokeLater(() -> { if (view.get() != null) view.get().accept(next); });
        };
        source.monitorSnapshotProperty().addListener(listener);
        return () -> {
            SwingServerMonitor panel = new SwingServerMonitor(i18n, type -> {
                CompletableFuture<String> result = new CompletableFuture<>();
                com.jlshell.ui.support.FxThread.run(() -> {
                    try { source.queryMonitorDetail(type).whenComplete((text, error) -> {
                        if (error == null) result.complete(text); else result.completeExceptionally(error);
                    }); } catch (Throwable error) { result.completeExceptionally(error); }
                });
                return result;
            });
            panel.warningThreshold = warning;
            panel.dangerThreshold = danger;
            view.set(panel);
            panel.unbind = () -> com.jlshell.ui.support.FxThread.run(() -> source.monitorSnapshotProperty().removeListener(listener));
            panel.accept(latest.get());
            return panel;
        };
    }

    SwingServerMonitor(I18nService i18n, Function<String, CompletableFuture<String>> query) {
        super(new BorderLayout(0, 4));
        this.i18n = i18n;
        this.query = query;
        setName("monitor.native");
        setBorder(BorderFactory.createEmptyBorder(3, 10, 5, 10));
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        host.setName("monitor.host");
        host.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        host.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showDetails("os", i18n.get("sysinfo.os"), host); }
        });
        state.setName("monitor.state");
        host.setFont(host.getFont().deriveFont(Font.PLAIN));
        state.setFont(state.getFont().deriveFont(Font.PLAIN));
        secondary(state);
        header.add(host, BorderLayout.CENTER);
        JPanel statusLine = new JPanel(new BorderLayout(6, 0));
        statusLine.setOpaque(false);
        JLabel info = new JLabel(new WorkbenchIcon(WorkbenchIcon.Kind.INFO));
        info.setName("monitor.info");
        info.setToolTipText(i18n.get("monitor.interval"));
        info.getAccessibleContext().setAccessibleName(i18n.get("monitor.interval"));
        statusLine.add(state, BorderLayout.CENTER);
        statusLine.add(info, BorderLayout.EAST);
        header.add(statusLine, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        JPanel grid = new MetricGrid();
        grid.setOpaque(false);
        JScrollPane viewport = new JScrollPane(grid);
        viewport.setBorder(BorderFactory.createEmptyBorder());
        viewport.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        viewport.getVerticalScrollBar().setUnitIncrement(16);
        add(viewport);
        for (String type : new String[]{"cpu", "mem", "ip", "disk"}) {
            Metric metric = new Metric(type, i18n.get("sysinfo." + (type.equals("ip") ? "network" : type)));
            metrics.put(type, metric);
            grid.add(metric);
        }
        host.setText(i18n.get("monitor.waiting"));
        state.setText("");
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        detailTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        content.add(detailTitle, BorderLayout.NORTH);
        detailText.setEditable(false);
        detailText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("defaultFont").getSize()));
        detailText.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(detailText);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.add(scroll);
        processRows.setColumnIdentifiers(new String[]{i18n.get("sysinfo.process.pid"), i18n.get("sysinfo.process.user"),
                "CPU %", i18n.get("sysinfo.process.memoryPercent"), "RSS KB", i18n.get("sysinfo.process.command")});
        int[] widths = {54, 80, 60, 60, 80, 220};
        for (int i = 0; i < widths.length; i++) processTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        processTable.setName("monitor.processes");
        processTable.setFillsViewportHeight(true);
        processTable.setRowHeight(Math.max(24, processTable.getRowHeight()));
        processScroll.setPreferredSize(new Dimension(580, 180));
        processScroll.setBorder(BorderFactory.createEmptyBorder());
        processTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = processTable.rowAtPoint(e.getPoint()), column = processTable.columnAtPoint(e.getPoint());
                if (row >= 0 && column == 0) {
                    String pid = String.valueOf(processTable.getValueAt(row, column));
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(pid), null);
                    processTable.setToolTipText(i18n.get("sysinfo.process.copied"));
                }
            }
        });
        processTable.setToolTipText(i18n.get("sysinfo.process.copyHint"));
        body.add(processScroll, BorderLayout.SOUTH);
        content.add(body);
        details.add(content);
        details.setName("monitor.details");
        details.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { cancelDetails(); }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { cancelDetails(); }
        });
        detailTimer = new Timer(3000, e -> {
            if (details.isVisible() && ("cpu".equals(detailType) || "mem".equals(detailType))) refreshDetails();
        });
    }

    private static void secondary(JLabel label) {
        label.putClientProperty("JLShell.secondaryForeground", true);
        label.setForeground(SwingTheme.color("secondary"));
    }

    void accept(ServerMonitorSnapshot value) {
        if (closed || value == null) return;
        host.setText(value.host() + "  ·  " + value.os());
        host.setToolTipText(value.address() + " · " + value.networkInterface() + " · " + value.kernel());
        state.setText(disconnected ? i18n.get("monitor.disconnected") : value.failure() == null
                ? value.clock() : i18n.get("monitor.stale"));
        state.setToolTipText(value.failure());
        boolean next = value.sample() != sample && value.failure() == null;
        metrics.get("cpu").accept(value.cpu(), next);
        metrics.get("mem").accept(value.memory(), next);
        metrics.get("ip").accept(value.network(), next);
        metrics.get("disk").accept(value.disk(), next);
        sample = value.sample();
    }

    void disconnected() {
        disconnected = true;
        state.setText(i18n.get("monitor.disconnected"));
        cancelDetails();
        details.setVisible(false);
        metrics.values().forEach(metric -> metric.setEnabled(false));
    }

    private void showDetails(String type, String title, JComponent anchor) {
        if (closed || disconnected) return;
        if (details.isVisible() && type.equals(detailType)) { details.setVisible(false); return; }
        details.setVisible(false);
        detailType = type;
        detailTitle.setText(title + "  ·  " + host.getText());
        detailText.setText(i18n.get("monitor.loading"));
        processRows.setRowCount(0);
        processScroll.setVisible("cpu".equals(detailType) || "mem".equals(detailType));
        SwingTheme.update(details);
        detailText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("defaultFont").getSize()));
        Dimension size = details.getPreferredSize();
        Rectangle screen = getGraphicsConfiguration().getBounds();
        size.width = Math.min(size.width, screen.width - 40);
        size.height = Math.min(size.height, screen.height / 2);
        details.setPopupSize(size);
        details.show(anchor, 0, -size.height - 4);
        refreshDetails();
        detailTimer.start();
    }

    private void refreshDetails() {
        if (closed || inFlight || detailType == null) return;
        inFlight = true;
        long current = ++request;
        query.apply(detailType).whenComplete((text, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || request != current) return;
            inFlight = false;
            if (!details.isVisible()) return;
            int position = detailText.getCaretPosition();
            String result = error != null ? i18n.get("monitor.failed")
                    : text == null || text.isBlank() ? i18n.get("monitor.empty") : text.strip();
            String[] sections = result.split("---PROCESSES---", 2);
            detailText.setText(sections[0].strip());
            processRows.setRowCount(0);
            if (sections.length == 2) for (String[] row : parseProcesses(sections[1])) processRows.addRow(row);
            processScroll.setVisible(sections.length == 2);
            detailText.setRows(Math.max(2, Math.min(8, detailText.getLineCount())));
            processScroll.setPreferredSize(new Dimension(580,
                    processTable.getTableHeader().getPreferredSize().height + Math.max(1, processRows.getRowCount()) * processTable.getRowHeight() + 4));
            Point location = details.getLocationOnScreen();
            int previousHeight = details.getHeight();
            details.setPreferredSize(null);
            Dimension preferred = details.getPreferredSize();
            preferred.width = Math.min(preferred.width, getGraphicsConfiguration().getBounds().width - 40);
            preferred.height = Math.min(preferred.height, getGraphicsConfiguration().getBounds().height / 2);
            details.setPopupSize(preferred);
            details.setLocation(location.x, Math.max(getGraphicsConfiguration().getBounds().y + 8, location.y + previousHeight - preferred.height));
            details.revalidate();
            detailText.setCaretPosition(Math.min(position, detailText.getDocument().getLength()));
        }));
    }

    static java.util.List<String[]> parseProcesses(String text) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] values = line.strip().split("\\s+", 6);
            if (values.length == 6 && values[0].matches("\\d+")) rows.add(values);
            if (rows.size() >= 10) break;
        }
        return rows;
    }

    private void cancelDetails() { request++; inFlight = false; detailType = null; if (detailTimer != null) detailTimer.stop(); }
    public void close() { closed = true; cancelDetails(); details.setVisible(false); unbind.run(); }

    private final class Metric extends JButton {
        private final String type;
        private final JLabel title, value = new JLabel("—"), detail = new JLabel("—");
        private final ArrayDeque<Double> history = new ArrayDeque<>();
        private int percent = -1;
        private boolean compact;

        void setCompact(boolean compact) {
            Font font = UIManager.getFont("defaultFont");
            Font selectedFont = font.deriveFont(Font.PLAIN, font.getSize2D() + (compact ? 0 : type.equals("ip") ? 1 : 9));
            Font titleFont = font.deriveFont(Font.BOLD);
            Font detailFont = font.deriveFont(Font.PLAIN);
            int bottom = compact ? 6 : 26;
            if (this.compact == compact && value.getFont().equals(selectedFont) && title.getFont().equals(titleFont)
                    && detail.getFont().equals(detailFont) && getInsets().bottom == bottom) return;
            this.compact = compact;
            putClientProperty("monitor.chartsVisible", !compact);
            removeAll();
            figures.removeAll();
            setBorder(BorderFactory.createEmptyBorder(6, 10, bottom, 10));
            value.setFont(selectedFont);
            title.setFont(titleFont);
            detail.setFont(detailFont);
            if (compact) {
                setLayout(new GridBagLayout());
                GridBagConstraints c = new GridBagConstraints();
                c.gridy = 0;
                c.anchor = GridBagConstraints.BASELINE_LEADING;
                c.insets = new Insets(0, 0, 0, 8);
                add(title, c);
                c.gridx = 1;
                c.insets = new Insets(0, 0, 0, 10);
                add(value, c);
                c.gridx = 2;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(0, 0, 0, 0);
                add(detail, c);
            } else {
                setLayout(new BorderLayout(0, 4));
                figures.add(value, BorderLayout.CENTER);
                figures.add(detail, BorderLayout.SOUTH);
                add(title, BorderLayout.NORTH);
                add(figures);
            }
        }
        private final JPanel figures = new JPanel(new BorderLayout(0, 4));
        Metric(String type, String name) {
            this.type = type;
            setName("monitor." + type);
            setLayout(new BorderLayout(0, 6));
            setBorder(BorderFactory.createEmptyBorder(10, 12, 24, 12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            title = new JLabel(name);
            secondary(title);
            secondary(detail);
            value.setFont(value.getFont().deriveFont(Font.BOLD, type.equals("ip") ? 16f : 24f));
            figures.setOpaque(false);
            figures.add(value, BorderLayout.CENTER);
            figures.add(detail, BorderLayout.SOUTH);
            add(title, BorderLayout.NORTH);
            add(figures);
            setCompact(false);
            setToolTipText(i18n.get("terminal.statusBar.details"));
            getAccessibleContext().setAccessibleName(name);
            addActionListener(e -> showDetails(type, title.getText(), this));
        }
        void accept(ServerMonitorSnapshot.Resource resource, boolean next) {
            value.setText(resource.value());
            detail.setText(resource.detail());
            detail.setToolTipText(resource.detail());
            setToolTipText(title.getText() + "  " + resource.value() + "  ·  " + resource.detail());
            percent = resource.percent();
            getAccessibleContext().setAccessibleDescription(resource.value() + " · " + resource.detail());
            if (next && resource.activity() >= 0) {
                history.addLast(resource.activity());
                while (history.size() > 45) history.removeFirst();
            }
            repaint();
        }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = UIManager.getColor("JLShell.formRaised");
                g.setColor(base);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                if (getModel().isRollover() || hasFocus()) {
                    g.setColor(UIManager.getColor("JLShell.formHoverBorder"));
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                Color accent = percent >= dangerThreshold ? new Color(0xd45d62) : percent >= warningThreshold ? SwingTheme.color("yellow") : UIManager.getColor("Component.focusColor");
                if (accent == null) accent = UIManager.getColor("Component.focusColor");
                g.setColor(accent);
                int y = getHeight() - 12, width = Math.max(0, getWidth() - 24);
                if (!compact && history.size() > 1) {
                    double max = type.equals("ip") ? Math.max(1024, history.stream().mapToDouble(Double::doubleValue).max().orElse(1)) : 100;
                    Path2D line = new Path2D.Double();
                    int i = 0;
                    for (double point : history) {
                        double x = 12 + (width * i / (double) Math.max(1, history.size() - 1));
                        double py = y - Math.max(0, Math.min(1, point / max)) * 12;
                        if (i++ == 0) line.moveTo(x, py); else line.lineTo(x, py);
                    }
                    g.setStroke(new BasicStroke(1.3f));
                    g.draw(line);
                } else if (!compact) {
                    g.setColor(UIManager.getColor("Separator.foreground"));
                    g.drawLine(12, y, 12 + width, y);
                }
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    }

    private static final class MetricGrid extends JPanel implements Scrollable {
        MetricGrid() { super(new ResponsiveGrid()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() >= getPreferredSize().height; }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(16, visible.height - 16); }
    }

    /** Column count depends on the viewport, with equal-width tiles and no horizontal scrolling. */
    private static final class ResponsiveGrid implements LayoutManager {
        public void addLayoutComponent(String name, Component component) {}
        public void removeLayoutComponent(Component component) {}
        private int columns(Container parent) {
            int width = parent.getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : parent.getWidth();
            return width <= 0 || width >= 800 ? 4 : width >= 420 ? 2 : 1;
        }
        private int minimumRowHeight(Container parent) { return Math.max(32, parent.getFontMetrics(parent.getFont()).getHeight() + 14); }
        public Dimension preferredLayoutSize(Container parent) {
            int rows = (parent.getComponentCount() + columns(parent) - 1) / columns(parent);
            return new Dimension(800, rows * minimumRowHeight(parent) + (rows - 1) * 8);
        }
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(180, minimumRowHeight(parent)); }
        public void layoutContainer(Container parent) {
            int cols = columns(parent), rows = (parent.getComponentCount() + cols - 1) / cols;
            int width = Math.max(0, (parent.getWidth() - (cols - 1) * 8) / cols);
            int height = Math.max(0, (parent.getHeight() - (rows - 1) * 8) / rows);
            for (int i = 0; i < parent.getComponentCount(); i++) {
                Component child = parent.getComponent(i);
                if (child instanceof SwingServerMonitor.Metric metric) metric.setCompact(height < 100);
                child.setBounds((i % cols) * (width + 8), (i / cols) * (height + 8), width, height);
            }
        }
    }
}
