// Copyright 2000-2019 JetBrains s.r.o. Apache-2.0.
// Ported from MouseMovementTracker.java and ScreenUtil.isMovementTowards().
package com.jlshell.ui.swing.idea;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/** 子菜单方向保护：鼠标斜向进入子菜单时，不因经过父菜单相邻条目而关闭它。 */
final class IdeaMouseMovementTracker {
    private final Point[] history = new Point[4];
    private int currentIndex;
    void reset() { Arrays.fill(history, null); }
    boolean isMovingTowards(MouseEvent event, Rectangle bounds) {
        Point location = event.getLocationOnScreen(), previous = null;
        for (int i = 0; i < history.length; i++) {
            Point point = history[(currentIndex - i + history.length) % history.length];
            if (point != null && point.distance(location) >= 2) { previous = point; break; }
        }
        Point last = history[currentIndex];
        if (last == null || last.distance(location) >= 2) {
            currentIndex = (currentIndex + 1) % history.length;
            history[currentIndex] = location;
        }
        return isMovementTowards(previous, location, bounds);
    }
    private static boolean isMovementTowards(Point previous, Point location, Rectangle bounds) {
        if (bounds == null) return false;
        if (previous == null || previous.equals(location) || bounds.contains(location)) return true;
        int dx = previous.x - location.x, dy = previous.y - location.y;
        if (dx > 0 && bounds.x >= previous.x || dx < 0 && bounds.x + bounds.width <= previous.x
                || dy < 0 && bounds.y + bounds.height <= previous.y || dy > 0 && bounds.y >= previous.y) return false;
        if (dx == 0) return location.x >= bounds.x && location.x < bounds.x + bounds.width && (dy > 0 ^ bounds.y > location.y);
        if (dy == 0) return location.y >= bounds.y && location.y < bounds.y + bounds.height && (dx > 0 ^ bounds.x > location.x);
        float a = (float) dy / dx, b = location.y - a * location.x;
        float crossY = a * bounds.x + b;
        if (crossY >= bounds.y && crossY < bounds.y + bounds.height) return true;
        crossY = a * (bounds.x + bounds.width) + b;
        if (crossY >= bounds.y && crossY < bounds.y + bounds.height) return true;
        float crossX = (bounds.y - b) / a;
        if (crossX >= bounds.x && crossX < bounds.x + bounds.width) return true;
        crossX = (bounds.y + bounds.height - b) / a;
        return crossX >= bounds.x && crossX < bounds.x + bounds.width;
    }
}
