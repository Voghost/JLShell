package com.jlshell.sysmon;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 2×2 dashboard: CPU | Memory | Network | Disk with live trend charts.
 * Defaults to remote monitoring when an SSH session is available.
 */
public class MonitorDashboard {

    private static final Duration POLL_INTERVAL = Duration.seconds(2);
    private static final Color CPU_COLOR = Color.web("#4d9cf8");
    private static final Color MEM_COLOR = Color.web("#7ec98a");
    private static final Color NET_COLOR = Color.web("#e8a838");
    private static final Color DISK_COLOR = Color.web("#c084fc");

    // Theme-aware colors
    private static final String DARK_BG = "#1e1f22";
    private static final String LIGHT_BG = "#f5f5f5";
    private static final String DARK_TEXT = "#dfe1e5";
    private static final String LIGHT_TEXT = "#1e1f22";
    private static final String DARK_DETAIL = "#6b6e73";
    private static final String LIGHT_DETAIL = "#9da0a8";
    private static final String DARK_CARD_BG = "rgba(255,255,255,0.04)";
    private static final String LIGHT_CARD_BG = "rgba(0,0,0,0.04)";

    private final MetricCard cpuCard = new MetricCard("CPU", CPU_COLOR, "%");
    private final MetricCard memCard = new MetricCard("Memory", MEM_COLOR, "%");
    private final MetricCard netCard = new MetricCard("Network", NET_COLOR, "", 1_000_000);
    private final MetricCard diskCard = new MetricCard("Disk", DISK_COLOR, "%");

    private MetricsCollector localCollector;
    private final AtomicReference<RemoteMetricsCollector> remoteCollector = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sysmon-collector");
        t.setDaemon(true);
        return t;
    });

    private Timeline timeline;
    private ToggleButton localBtn;
    private ToggleButton remoteBtn;
    private volatile boolean remoteMode = true;
    private volatile boolean isDarkTheme = true;
    private volatile Locale currentLocale = Locale.ENGLISH;

    private BorderPane root;
    private Label titleLabel;
    private Label sourceLabel;

    // For network rate calculation
    private long prevNetRecv = -1;
    private long prevNetSent = -1;
    private long prevTimestamp = -1;

    public Node createView(PluginContext context) {
        boolean sshAvailable = context.sshSession().isPresent();
        isDarkTheme = "dark".equals(context.themeName());

        // Default to remote if SSH is available
        remoteMode = sshAvailable;

        // Header
        titleLabel = new Label("System Monitor");
        titleLabel.setStyle("-fx-text-fill: " + (isDarkTheme ? DARK_TEXT : LIGHT_TEXT) + "; -fx-font-size: 16px; -fx-font-weight: bold;");

        localBtn = new ToggleButton("Local");
        localBtn.setStyle("-fx-font-size: 11px;");
        remoteBtn = new ToggleButton("Remote");
        remoteBtn.setStyle("-fx-font-size: 11px;");

        if (!sshAvailable) {
            remoteBtn.setDisable(true);
            remoteBtn.setTooltip(new javafx.scene.control.Tooltip("Connect to an SSH session first"));
        }

        ToggleGroup group = new ToggleGroup();
        localBtn.setToggleGroup(group);
        remoteBtn.setToggleGroup(group);

        // Set default selection
        if (sshAvailable) {
            remoteBtn.setSelected(true);
            remoteCollector.set(new RemoteMetricsCollector(context.sshSession().get()));
        } else {
            localBtn.setSelected(true);
            localCollector = new MetricsCollector();
        }

        group.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == localBtn) {
                remoteMode = false;
                if (localCollector == null) localCollector = new MetricsCollector();
            } else if (toggle == remoteBtn) {
                if (context.sshSession().isPresent()) {
                    remoteMode = true;
                    remoteCollector.set(new RemoteMetricsCollector(context.sshSession().get()));
                } else {
                    localBtn.setSelected(true);
                    remoteMode = false;
                }
            }
            prevNetRecv = -1;
            prevNetSent = -1;
            prevTimestamp = -1;
            clearAllCharts();
        });

        // Source indicator
        sourceLabel = new Label();
        sourceLabel.setStyle("-fx-text-fill: " + (isDarkTheme ? DARK_DETAIL : LIGHT_DETAIL) + "; -fx-font-size: 11px;");
        updateSourceLabel();

        group.selectedToggleProperty().addListener((obs, old, toggle) -> updateSourceLabel());

        HBox toggleRow = new HBox(8, localBtn, remoteBtn, sourceLabel);
        toggleRow.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(16, titleLabel, toggleRow);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));

        // 2×2 grid
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.add(cpuCard, 0, 0);
        grid.add(memCard, 1, 0);
        grid.add(netCard, 0, 1);
        grid.add(diskCard, 1, 1);

        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        col.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col, col);

        root = new BorderPane();
        root.setTop(header);
        root.setCenter(grid);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: " + (isDarkTheme ? DARK_BG : LIGHT_BG) + ";");

        applyThemeToCards();

        startPolling();
        return root;
    }

    public void applyTheme(String themeName) {
        isDarkTheme = "dark".equals(themeName);
        Platform.runLater(() -> {
            if (root != null) {
                root.setStyle("-fx-background-color: " + (isDarkTheme ? DARK_BG : LIGHT_BG) + ";");
            }
            if (titleLabel != null) {
                titleLabel.setStyle("-fx-text-fill: " + (isDarkTheme ? DARK_TEXT : LIGHT_TEXT) + "; -fx-font-size: 16px; -fx-font-weight: bold;");
            }
            if (sourceLabel != null) {
                sourceLabel.setStyle("-fx-text-fill: " + (isDarkTheme ? DARK_DETAIL : LIGHT_DETAIL) + "; -fx-font-size: 11px;");
            }
            applyThemeToCards();
        });
    }

    private void applyThemeToCards() {
        String textFill = isDarkTheme ? DARK_TEXT : LIGHT_TEXT;
        String detailFill = isDarkTheme ? DARK_DETAIL : LIGHT_DETAIL;
        String cardBg = isDarkTheme ? DARK_CARD_BG : LIGHT_CARD_BG;
        cpuCard.setThemeColors(textFill, detailFill, cardBg);
        memCard.setThemeColors(textFill, detailFill, cardBg);
        netCard.setThemeColors(textFill, detailFill, cardBg);
        diskCard.setThemeColors(textFill, detailFill, cardBg);
    }

    public void applyLocale(Locale locale) {
        currentLocale = locale;
        ResourceBundle bundle = getBundle(locale);
        Platform.runLater(() -> {
            cpuCard.setTitle(bundle.getString("metric.cpu"));
            memCard.setTitle(bundle.getString("metric.memory"));
            netCard.setTitle(bundle.getString("metric.network"));
            diskCard.setTitle(bundle.getString("metric.disk"));
            if (titleLabel != null) {
                titleLabel.setText(bundle.getString("plugin.name"));
            }
            updateSourceLabel();
        });
    }

    private ResourceBundle getBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle("com.jlshell.sysmon.messages", locale);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("com.jlshell.sysmon.messages", Locale.ENGLISH);
        }
    }

    private void updateSourceLabel() {
        if (sourceLabel == null) return;
        if (remoteMode) {
            sourceLabel.setText("● Monitoring: Remote server");
        } else {
            sourceLabel.setText("● Monitoring: Local machine");
        }
    }

    public void startPolling() {
        if (timeline != null) timeline.stop();
        timeline = new Timeline(new KeyFrame(POLL_INTERVAL, e -> poll()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        poll();
    }

    public void stopPolling() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }

    private void poll() {
        if (remoteMode && remoteCollector.get() != null) {
            remoteCollector.get().collect()
                    .thenAccept(m -> Platform.runLater(() -> updateUI(m)))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            cpuCard.updateValue("Error");
                            cpuCard.updateDetail(ex.getMessage());
                        });
                        return null;
                    });
        } else {
            executor.submit(() -> {
                try {
                    SystemMetrics m = localCollector.collect();
                    Platform.runLater(() -> updateUI(m));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        cpuCard.updateValue("Error");
                        cpuCard.updateDetail(ex.getMessage());
                    });
                }
            });
        }
    }

    private void updateUI(SystemMetrics m) {
        // CPU
        cpuCard.updateValue(String.format("%.1f%%", m.cpuUsage()));
        cpuCard.updateDetail(String.format("%d cores · load avg %.2f", m.cpuCores(), m.cpuLoadAvg1m()));
        cpuCard.addChartSample(m.cpuUsage());

        // Memory
        memCard.updateValue(String.format("%.1f%%", m.memUsagePercent()));
        memCard.updateDetail(formatBytes(m.memUsed()) + " / " + formatBytes(m.memTotal()));
        memCard.addChartSample(m.memUsagePercent());

        // Network — calculate rate from delta
        long elapsed = prevTimestamp > 0 ? (m.timestamp() - prevTimestamp) / 1000 : 2;
        if (elapsed <= 0) elapsed = 2;
        long recvRate = 0, sentRate = 0;
        if (prevNetRecv >= 0) {
            recvRate = (m.netBytesRecv() - prevNetRecv) / elapsed;
            sentRate = (m.netBytesSent() - prevNetSent) / elapsed;
        }
        prevNetRecv = m.netBytesRecv();
        prevNetSent = m.netBytesSent();
        prevTimestamp = m.timestamp();

        netCard.updateValue(formatRate(recvRate) + " ↓  " + formatRate(sentRate) + " ↑");
        netCard.updateDetail("Total: " + formatBytes(m.netBytesRecv()) + " recv · " + formatBytes(m.netBytesSent()) + " sent");
        // Chart: show total throughput rate in bytes/sec
        double totalRate = recvRate + sentRate;
        netCard.addChartSample(totalRate);
        // Dynamically adjust Y axis for network
        double yMax = Math.max(1_000_000, totalRate * 1.5);
        netCard.getChart().setYRange(0, yMax);

        // Disk
        if (!m.disks().isEmpty()) {
            SystemMetrics.DiskInfo primary = m.disks().get(0);
            diskCard.updateValue(String.format("%.1f%%", primary.usagePercent()));
            diskCard.updateDetail(primary.mount() + ": " + formatBytes(primary.used()) + " / " + formatBytes(primary.total()));
            diskCard.addChartSample(primary.usagePercent());
        } else {
            diskCard.updateValue("--");
            diskCard.updateDetail("No disk info");
        }
    }

    private void clearAllCharts() {
        cpuCard.clearChart();
        memCard.clearChart();
        netCard.clearChart();
        diskCard.clearChart();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024));
    }
}