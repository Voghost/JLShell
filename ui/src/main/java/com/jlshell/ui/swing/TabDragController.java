package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 对照 IDEA DragHelper 的按下时锁定来源、抓取偏移、跨标签中点重排和局部重绘。 */
final class TabDragController extends MouseAdapter implements AutoCloseable {
    private final JTabbedPane tabs;
    private final Consumer<Component> select;
    private final BiConsumer<Component, Integer> commit;
    private final BiConsumer<Component, Integer> move;
    private final Timer animation = new Timer(16, event -> animate());
    private final Map<Component, Motion> motions = new LinkedHashMap<>();
    private final Map<Component, Integer> originalOrder = new LinkedHashMap<>();
    private Component source;
    private Component header;
    private Point pressed;
    private int grabOffset;
    private int originalIndex;
    private boolean dragging;
    private boolean settling;
    private long started;
    private float ghostX;
    private float settleFrom;
    private float settleTo;
    private BufferedImage image;
    private Rectangle strip;
    private final JPanel overlay = new JPanel() {
        @Override public boolean contains(int x, int y) { return false; }
        @Override protected void paintComponent(Graphics graphics) {
            if (image == null || source == null) return;
            int index = tabs.indexOfComponent(source);
            if (index < 0) return;
            Rectangle slot = tabs.getBoundsAt(index);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(SwingTheme.color("canvas"));
                g.fillRect(slot.x - strip.x, 0, slot.width, getHeight());
                g.setColor(UIManager.getColor("Component.focusColor"));
                g.fillRect(slot.x - strip.x + 3, getHeight() - 2, Math.max(0, slot.width - 6), 2);
                g.drawImage(image, Math.round(ghostX) - strip.x, 0, slot.width, strip.height, null);
            } finally { g.dispose(); }
        }
    };
    private record Motion(Point from, Point to) {}
    private final KeyEventDispatcher escape = event -> {
        if (dragging && event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancel();
            return true;
        }
        return false;
    };

    TabDragController(JTabbedPane tabs, Consumer<Component> select, BiConsumer<Component, Integer> commit,
                      BiConsumer<Component, Integer> move) {
        this.tabs = tabs; this.select = select; this.commit = commit; this.move = move;
        overlay.setOpaque(false);
        install(tabs);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape);
    }

    void install(Component component) {
        component.addMouseListener(this);
        component.addMouseMotionListener(this);
    }

    @Override public void mousePressed(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event)) return;
        finish();
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), tabs);
        int index = tabs.indexAtLocation(point.x, point.y);
        if (index < 0) return;
        source = tabs.getComponentAt(index);
        originalIndex = index;
        pressed = point;
        header = tabs.getTabComponentAt(index);
        grabOffset = point.x - tabs.getBoundsAt(index).x;
        originalOrder.clear();
        for (int i = 0; i < tabs.getTabCount(); i++) originalOrder.put(tabs.getComponentAt(i), i);
        select.accept(source);
    }

    @Override public void mouseDragged(MouseEvent event) {
        if (source == null || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return;
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), tabs);
        if (!dragging) {
            if (pressed.distance(point) < 5) return;
            int index = tabs.indexOfComponent(source);
            if (index < 0 || tabs.getRootPane() == null) { finish(); return; }
            strip = tabs.getBoundsAt(index);
            double scale = tabs.getGraphicsConfiguration() == null ? 1
                    : tabs.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            image = new BufferedImage((int) Math.ceil(strip.width * scale),
                    (int) Math.ceil(strip.height * scale), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try { g.scale(scale, scale); g.translate(-strip.x, -strip.y); tabs.paint(g); }
            finally { g.dispose(); }
            strip = new Rectangle(0, strip.y, tabs.getWidth(), strip.height);
            overlay.setBounds(SwingUtilities.convertRectangle(tabs, strip, tabs.getRootPane().getLayeredPane()));
            tabs.getRootPane().getLayeredPane().add(overlay, JLayeredPane.DRAG_LAYER);
            // 覆盖层不拦截鼠标，保留原控件的鼠标捕获与焦点。
            // 不在拖动中隐藏按下的标签，避免丢失后续拖动/松手事件。
            dragging = true;
        }
        int current = tabs.indexOfComponent(source);
        if (current < 0) { finish(); return; }
        Rectangle bounds = tabs.getBoundsAt(current);
        ghostX = Math.max(0, Math.min(point.x - grabOffset, tabs.getWidth() - bounds.width));
        float center = ghostX + bounds.width / 2f;
        int target = current;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Rectangle candidate = tabs.getBoundsAt(i);
            if (i > current && center > candidate.getCenterX()) target = i;
            if (i < current && center < candidate.getCenterX()) { target = i; break; }
        }
        if (target != current) {
            Map<Component, Point> before = new LinkedHashMap<>();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component item = tabs.getTabComponentAt(i);
                if (item != null && item != header) before.put(item, item.getLocation());
            }
            move.accept(source, target);
            tabs.validate();
            motions.clear();
            before.forEach((item, location) -> motions.put(item, new Motion(location, item.getLocation())));
            started = System.nanoTime();
            animation.restart();
        }
        overlay.repaint();
        event.consume();
    }

    @Override public void mouseReleased(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event) || source == null) return;
        if (!dragging) { finish(); return; }
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), tabs);
        if (point.y < strip.y - 24 || point.y > strip.y + strip.height + 24) { cancel(); return; }
        int index = tabs.indexOfComponent(source);
        if (index < 0) { finish(); return; }
        if (index != originalIndex) commit.accept(source, index);
        dragging = false;
        settling = true;
        settleFrom = ghostX;
        settleTo = tabs.getBoundsAt(index).x;
        started = System.nanoTime();
        animation.restart();
        event.consume();
    }

    private void animate() {
        double elapsed = Math.min(1, (System.nanoTime() - started) / 160_000_000d);
        double remaining = Math.pow(1 - elapsed, 3);
        motions.forEach((item, motion) -> item.setLocation(
                motion.to.x + (int) Math.round((motion.from.x - motion.to.x) * remaining),
                motion.to.y + (int) Math.round((motion.from.y - motion.to.y) * remaining)));
        if (settling) ghostX = (float) (settleTo + (settleFrom - settleTo) * remaining);
        if (strip != null) tabs.repaint(strip);
        overlay.repaint();
        if (elapsed >= 1) {
            motions.clear();
            animation.stop();
            if (settling) finish();
        }
    }

    private void cancel() {
        originalOrder.forEach(move);
        finish();
    }

    private void finish() {
        animation.stop();
        motions.forEach((item, motion) -> item.setLocation(motion.to));
        motions.clear();
        if (overlay.getParent() != null) {
            Container parent = overlay.getParent();
            Rectangle bounds = overlay.getBounds();
            parent.remove(overlay);
            parent.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        source = null; header = null; image = null; strip = null;
        originalOrder.clear();
        dragging = false; settling = false;
    }

    @Override public void close() {
        finish();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape);
    }
}
