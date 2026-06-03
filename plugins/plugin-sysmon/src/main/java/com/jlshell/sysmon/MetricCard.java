package com.jlshell.sysmon;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Card showing a single metric: title, current value, sub-detail, and a trend chart.
 */
public class MetricCard extends VBox {

    private final Label titleLabel;
    private final Label valueLabel;
    private final Label detailLabel;
    private final TrendChart chart;

    public MetricCard(String title, Color chartColor, String unit) {
        this(title, chartColor, unit, 100);
    }

    public MetricCard(String title, Color chartColor, String unit, double yMax) {
        setSpacing(6);
        setPadding(new Insets(10, 14, 10, 14));
        setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 8;");

        titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: #9da0a8; -fx-font-size: 11px; -fx-font-weight: bold;");

        valueLabel = new Label("--");
        valueLabel.setStyle("-fx-text-fill: #dfe1e5; -fx-font-size: 22px; -fx-font-weight: bold;");

        detailLabel = new Label("");
        detailLabel.setStyle("-fx-text-fill: #6b6e73; -fx-font-size: 10px;");
        detailLabel.setWrapText(true);

        chart = new TrendChart(title, chartColor, unit, yMax);

        getChildren().addAll(titleLabel, valueLabel, detailLabel, chart);
    }

    public void updateValue(String value) {
        valueLabel.setText(value);
    }

    public void updateDetail(String detail) {
        detailLabel.setText(detail);
    }

    public void addChartSample(double value) {
        chart.addSample(value);
    }

    public void clearChart() {
        chart.clear();
    }

    public TrendChart getChart() {
        return chart;
    }

    public void setThemeColors(String textFill, String detailFill, String cardBg) {
        valueLabel.setStyle("-fx-text-fill: " + textFill + "; -fx-font-size: 22px; -fx-font-weight: bold;");
        detailLabel.setStyle("-fx-text-fill: " + detailFill + "; -fx-font-size: 10px;");
        titleLabel.setStyle("-fx-text-fill: " + detailFill + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        setStyle("-fx-background-color: " + cardBg + "; -fx-background-radius: 8;");
        chart.setThemeColors(detailFill, cardBg);
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }
}