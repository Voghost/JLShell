package com.jlshell.ui.swing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned workspace preferences; plugin IDs are stable across sessions and page ordering. */
final class ToolDockState {
    enum Anchor { RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    static final String SETTING = "ui.workspace.toolLayout";
    final Map<String, Anchor> positions = new LinkedHashMap<>();
    int sidebar = 280, height = 112, width = 380, bottomPercent = 50;

    boolean leftVisible = true, bottomRightVisible, rightVisible;
    String leftSelected = "monitor", bottomRightSelected, rightSelected = "files";

    Anchor anchor(String key) { return positions.getOrDefault(key, "monitor".equals(key) ? Anchor.BOTTOM_LEFT : Anchor.RIGHT); }
    String encode() {
        StringBuilder value = new StringBuilder("1|" + sidebar + "|" + height + "|" + width + "|split=" + bottomPercent);
        value.append("|visible=").append(leftVisible ? '1' : '0').append(bottomRightVisible ? '1' : '0').append(rightVisible ? '1' : '0');
        value.append("|activeLeft=").append(key(leftSelected)).append("|activeBottomRight=").append(key(bottomRightSelected))
                .append("|activeRight=").append(key(rightSelected));
        positions.forEach((key, anchor) -> value.append('|').append(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8))).append(':').append(anchor.name()));
        return value.toString();
    }
    static ToolDockState decode(String value) {
        ToolDockState result = new ToolDockState();
        if (value == null || value.isBlank()) return result;
        try {
            String[] fields = value.split("\\|");
            if (!fields[0].equals("1") || fields.length < 4) return result;
            result.sidebar = bounded(fields[1], 180, 1200);
            result.height = bounded(fields[2], 96, 1200);
            result.width = bounded(fields[3], 180, 1600);
            for (int i = 4; i < fields.length; i++) {
                if (fields[i].startsWith("split=")) { result.bottomPercent = bounded(fields[i].substring(6), 15, 85); continue; }
                if (fields[i].startsWith("visible=")) {
                    String flags = fields[i].substring(8);
                    if (!flags.matches("[01]{3}")) throw new IllegalArgumentException("Invalid dock visibility");
                    result.leftVisible = flags.charAt(0) == '1'; result.bottomRightVisible = flags.charAt(1) == '1'; result.rightVisible = flags.charAt(2) == '1'; continue;
                }
                if (fields[i].startsWith("activeLeft=")) { result.leftSelected = readKey(fields[i].substring(11)); continue; }
                if (fields[i].startsWith("activeBottomRight=")) { result.bottomRightSelected = readKey(fields[i].substring(18)); continue; }
                if (fields[i].startsWith("activeRight=")) { result.rightSelected = readKey(fields[i].substring(12)); continue; }
                String[] pair = fields[i].split(":", 2);
                String key = new String(Base64.getUrlDecoder().decode(pair[0]), StandardCharsets.UTF_8);
                if (!key.isBlank() && key.length() <= 512) result.positions.put(key, Anchor.valueOf(pair[1]));
            }
            return result;
        } catch (RuntimeException invalid) { return new ToolDockState(); }
    }
    private static String key(String key) { return key == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)); }
    private static String readKey(String encoded) {
        String key = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        if (key.length() > 512) throw new IllegalArgumentException("Invalid tool key");
        return key.isEmpty() ? null : key;
    }
    private static int bounded(String value, int min, int max) { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
}
