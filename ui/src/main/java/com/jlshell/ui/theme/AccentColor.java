package com.jlshell.ui.theme;

import java.util.Arrays;

public enum AccentColor {
    SKY("SKY", "Sky Blue", "#4d9cf8", "#3670b8", "#1f3f63", "#dbeafe"),
    BLUE("BLUE", "Azure", "#3b82f6", "#2563eb", "#1e3a5f", "#dbeafe"),
    CYAN("CYAN", "Cyan", "#06b6d4", "#0891b2", "#164e63", "#cffafe"),
    TEAL("TEAL", "Teal", "#14b8a6", "#0d9488", "#134e4a", "#ccfbf1"),
    GREEN("GREEN", "Emerald", "#22c55e", "#16a34a", "#14532d", "#dcfce7"),
    VIOLET("VIOLET", "Violet", "#8b5cf6", "#7c3aed", "#3b236d", "#ede9fe"),
    ROSE("ROSE", "Rose", "#f43f5e", "#e11d48", "#6f1d2e", "#ffe4e6"),
    AMBER("AMBER", "Amber", "#f59e0b", "#d97706", "#65410b", "#fef3c7");

    private final String id;
    private final String displayName;
    private final String color;
    private final String hoverColor;
    private final String darkSubtleColor;
    private final String lightSubtleColor;

    AccentColor(String id, String displayName, String color, String hoverColor,
                String darkSubtleColor, String lightSubtleColor) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.hoverColor = hoverColor;
        this.darkSubtleColor = darkSubtleColor;
        this.lightSubtleColor = lightSubtleColor;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String color() {
        return color;
    }

    public String hoverColor() {
        return hoverColor;
    }

    public String subtleColor(AppTheme theme) {
        return theme == AppTheme.LIGHT ? lightSubtleColor : darkSubtleColor;
    }

    public static AccentColor fromId(String id) {
        return Arrays.stream(values())
                .filter(accent -> accent.id.equalsIgnoreCase(id))
                .findFirst()
                .orElse(SKY);
    }
}
