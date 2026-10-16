// Copyright 2000-2023 JetBrains s.r.o. and contributors. Apache-2.0.
// Border path adapted from RectanglePainter2D.java / PaintUtil.java.
package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.geom.Path2D;

/**
 * AbstractPopup / WindowRoundedCornersManager 的透明窗口表面适配。
 * IDEA 的 JBR 原生圆角半径为 8；OpenJDK 使用透明窗口和 Java2D 绘制同样的轮廓。
 */
final class IdeaPopupSurface extends JPanel {
    static final int SHADOW = 10;
    private static final int RADIUS = 8;
    private final boolean translucent;
    private final String backgroundKey, borderKey;

    IdeaPopupSurface(JComponent content, boolean translucent) {
        this(content, translucent, false);
    }

    IdeaPopupSurface(JComponent content, boolean translucent, boolean tooltip) {
        super(new BorderLayout());
        backgroundKey = tooltip ? "tooltip" : "popup";
        borderKey = tooltip ? "tooltipBorder" : "popupBorder";
        this.translucent = translucent;
        setName("idea.popupSurface");
        setOpaque(!translucent);
        setBackground(SwingTheme.color(backgroundKey));
        int margin = translucent ? SHADOW : 0;
        setBorder(BorderFactory.createEmptyBorder(margin + 1, margin + 1, margin + 1, margin + 1));
        add(content);
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (opaqueWindowsSurface()) {
                g.setColor(SwingTheme.color(backgroundKey));
                g.fillRect(0, 0, getWidth(), getHeight());
                return;
            }
            int inset = translucent ? SHADOW : 0;
            int width = getWidth() - inset * 2, height = getHeight() - inset * 2;
            if (translucent) {
                // 软件阴影只画在圆角轮廓之外，避免背景角落出现方形色块。
                for (int spread = SHADOW; spread > 0; spread--) {
                    g.setColor(new Color(0, 0, 0, 3 + (SHADOW - spread) / 2));
                    g.fill(new RoundRectangle2D.Double(inset - spread, inset - spread + 2,
                            width + spread * 2, height + spread * 2, (RADIUS + spread) * 2, (RADIUS + spread) * 2));
                }
            }
            g.setColor(SwingTheme.color(backgroundKey));
            g.fill(bodyShape(g));
        } finally { g.dispose(); }
    }
    @Override protected void paintChildren(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Windows 由 DWM 裁剪外轮廓，文字内容只保留矩形 clip，保持 LCD 渲染条件。
            if (opaqueWindowsSurface()) g.clipRect(1, 1, getWidth() - 2, getHeight() - 2);
            else g.clip(innerShape(bodyShape(g), borderWidth(g)));
            super.paintChildren(g);
        } finally { g.dispose(); }
        if (opaqueWindowsSurface() && getRootPane() != null
                && Boolean.TRUE.equals(getRootPane().getClientProperty(IdeaPopupWindow.NATIVE_BORDER))) return;
        // PopupBorder 使用单色、向内的 1px 描边；JBR 圆角管理器同样只接收一个边框色。
        // 在内容之后描边，避免子组件和非抗锯齿 clip 留下第二层亮边或覆盖圆角。
        g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SwingTheme.color(borderKey));
            // 对应 RectanglePainter2D.DRAW 的 INSIDE 分支：一次填充外/内轮廓之差。
            // 坐标与线宽按 PaintUtil.alignToInt 对齐设备像素，分数缩放也不产生半像素亮边。
            if (opaqueWindowsSurface()) {
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                return;
            }
            var outer = bodyShape(g);
            Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            path.append(outer, false);
            path.append(innerShape(outer, borderWidth(g)), false);
            g.fill(path);
        } finally { g.dispose(); }
    }

    private boolean opaqueWindowsSurface() {
        return com.formdev.flatlaf.util.SystemInfo.isWindows && !translucent;
    }

    private RoundRectangle2D.Double bodyShape(Graphics2D g) {
        double scale = deviceScale(g), margin = translucent ? SHADOW : 0;
        double x = Math.round(margin * scale) / scale;
        double right = Math.round((getWidth() - margin) * scale) / scale;
        double bottom = Math.round((getHeight() - margin) * scale) / scale;
        return new RoundRectangle2D.Double(x, x, right - x, bottom - x, RADIUS * 2, RADIUS * 2);
    }

    private static RoundRectangle2D.Double innerShape(RoundRectangle2D.Double outer, double width) {
        return new RoundRectangle2D.Double(outer.x + width, outer.y + width,
                outer.width - width * 2, outer.height - width * 2,
                outer.arcwidth - width * 2, outer.archeight - width * 2);
    }

    private static double borderWidth(Graphics2D g) {
        double scale = deviceScale(g);
        return Math.max(1, Math.round(scale)) / scale;
    }

    private static double deviceScale(Graphics2D g) {
        return Math.max(1, Math.abs(g.getTransform().getScaleX()));
    }
}
