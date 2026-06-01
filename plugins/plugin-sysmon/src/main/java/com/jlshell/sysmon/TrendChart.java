package com.jlshell.sysmon;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * JavaFX LineChart-based trend chart with proper axes, labels, and styled lines.
 */
public class TrendChart extends VBox {

    private static final int MAX_SAMPLES = 120;

    private final XYChart.Series<Number, Number> series;
    private final NumberAxis xAxis;
    private final NumberAxis yAxis;
    private final LineChart<Number, Number> chart;
    private final String unit;
    private int sampleIndex = 0;
    private boolean isDarkTheme = true;

    public TrendChart(String title, Color lineColor, String unit, double yMax) {
        this.unit = unit;

        xAxis = new NumberAxis(0, MAX_SAMPLES, 30);
        xAxis.setTickLabelsVisible(false);
        xAxis.setTickMarkVisible(false);
        xAxis.setMinorTickVisible(false);
        xAxis.setAutoRanging(false);

        yAxis = new NumberAxis(0, yMax, yMax / 4);
        yAxis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(yAxis) {
            @Override
            public String toString(Number value) {
                if ("B/s".equals(unit) || "".equals(unit)) {
                    return formatByteRate(value.doubleValue());
                }
                return String.format("%.0f%s", value.doubleValue(), unit);
            }
        });
        yAxis.setAutoRanging(false);
        yAxis.setPrefWidth(55);

        chart = new LineChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(false);
        chart.setMinHeight(140);
        chart.setPrefHeight(160);

        series = new XYChart.Series<>();
        chart.getData().add(series);

        // Style the line color
        String rgb = String.format("#%02X%02X%02X",
                (int) (lineColor.getRed() * 255),
                (int) (lineColor.getGreen() * 255),
                (int) (lineColor.getBlue() * 255));

        chart.setStyle("-fx-background-color: transparent; " +
                "-fx-padding: 0; " +
                "-fx-chart-background-color: rgba(255,255,255,0.03);");

        setSpacing(2);
        getChildren().addAll(chart);

        // Apply line color after scene is attached
        chart.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) applyLineColor(rgb);
        });
    }

    private void applyLineColor(String rgb) {
        for (Node n : chart.lookupAll(".chart-series-line")) {
            n.setStyle("-fx-stroke: " + rgb + "; -fx-stroke-width: 2px;");
        }
        applyThemeToChart();
    }

    private void applyThemeToChart() {
        String plotBg = isDarkTheme ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.03)";
        String gridColor = isDarkTheme ? "rgba(255,255,255,0.06)" : "rgba(0,0,0,0.08)";
        String tickColor = isDarkTheme ? "#6b6e73" : "#9da0a8";
        for (Node n : chart.lookupAll(".chart-plot-background")) {
            n.setStyle("-fx-background-color: " + plotBg + ";");
        }
        for (Node n : chart.lookupAll(".chart-horizontal-grid-lines")) {
            n.setStyle("-fx-stroke: " + gridColor + ";");
        }
        // Y-axis tick label color
        yAxis.setStyle("-fx-text-fill: " + tickColor + ";");
        yAxis.setTickLabelFill(javafx.scene.paint.Color.web(tickColor));
    }

    public void setThemeColors(String detailFill, String cardBg) {
        isDarkTheme = cardBg.contains("255,255,255");
        applyThemeToChart();
    }

    public void addSample(double value) {
        series.getData().add(new XYChart.Data<>(sampleIndex, value));
        sampleIndex++;

        // Scroll: keep only MAX_SAMPLES visible
        if (sampleIndex > MAX_SAMPLES) {
            xAxis.setLowerBound(sampleIndex - MAX_SAMPLES);
            xAxis.setUpperBound(sampleIndex);
            // Remove off-screen points to prevent memory growth
            while (series.getData().size() > MAX_SAMPLES) {
                series.getData().remove(0);
            }
        }
    }

    public void clear() {
        series.getData().clear();
        sampleIndex = 0;
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(MAX_SAMPLES);
    }

    public void setYRange(double min, double max) {
        yAxis.setLowerBound(min);
        yAxis.setUpperBound(max);
        yAxis.setTickUnit(max / 4);
    }

    private static String formatByteRate(double val) {
        if (val < 1024) return String.format("%.0f B/s", val);
        if (val < 1024 * 1024) return String.format("%.1f KB/s", val / 1024);
        return String.format("%.1f MB/s", val / (1024 * 1024));
    }
}