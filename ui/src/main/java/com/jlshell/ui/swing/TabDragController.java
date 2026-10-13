package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** IDEA-style tab drag: keep the grabbed tab under the pointer and paint split drop feedback over the workspace. */
final class TabDragController extends MouseAdapter implements AutoCloseable {
    private final JTabbedPane tabs;
    private final Consumer<Component> select;
    private final BiConsumer<Component, Integer> commit;
    private final BiConsumer<Component, Integer> move;
    private final java.util.function.BiPredicate<Component, Point> externalDrop;
    private final Function<Point, Rectangle> dropPreview;
    private final Timer animation = new Timer(16, event -> animate());
    private final Map<Component, Motion> motions = new LinkedHashMap<>();
    private final Map<Component, Integer> originalOrder = new LinkedHashMap<>();
    private Component source;
    private Component header;
    private Point pressed;
    private int grabOffsetX;
    private int grabOffsetY;
    private double ghostScale = 1;
    private int originalIndex;
    private boolean dragging;
    private boolean settling;
    private long started;
    private float ghostX;
    private float settleFrom;
    private float settleTo;
    private Point settlePointerFrom;
    private Point settlePointerTo;
    private BufferedImage image;
    private Rectangle strip;
    private Point pointer;
    private Rectangle preview;
    private JLayeredPane layeredPane;
    private final JPanel overlay = new JPanel() {
        @Override public boolean contains(int x, int y) { return false; }
        @Override protected void paintComponent(Graphics graphics) {
            if ((!dragging && !settling) || source == null || image == null || layeredPane == null || pointer == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                // Cover the selected source tab while its translucent drag chip follows the pointer.
                int index = tabs.indexOfComponent(source);
                if (index >= 0) {
                    Rectangle sourceBounds = SwingUtilities.convertRectangle(tabs, tabs.getBoundsAt(index), layeredPane);
                    g.setColor(SwingTheme.color("canvas"));
                    g.fillRect(sourceBounds.x, sourceBounds.y, sourceBounds.width, sourceBounds.height);
                }

                // A quiet, translucent half-pane preview communicates the pending split before the drop commits.
                if (preview != null) {
                    Color accent = UIManager.getColor("Component.focusColor");
                    if (accent == null) accent = new Color(53, 116, 240);
                    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
                    g.fillRoundRect(preview.x + 3, preview.y + 3, Math.max(0, preview.width - 6),
                            Math.max(0, preview.height - 6), 12, 12);
                    g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 155));
                    g.setStroke(new BasicStroke(1.4f));
                    g.drawRoundRect(preview.x + 3, preview.y + 3, Math.max(0, preview.width - 7),
                            Math.max(0, preview.height - 7), 12, 12);
                }

                int contentWidth = (int) Math.ceil(image.getWidth() / ghostScale);
                int contentHeight = (int) Math.ceil(image.getHeight() / ghostScale);
                int x = pointer.x - grabOffsetX - 7;
                int y = pointer.y - grabOffsetY - 4;
                int width = contentWidth + 14;
                int height = contentHeight + 8;
                Color panel = SwingTheme.color("panel");
                Color line = SwingTheme.color("border");
                if (line == null) line = new Color(90, 92, 98);
                g.setColor(new Color(0, 0, 0, 68));
                g.fillRoundRect(x + 2, y + 4, width, height, 11, 11);
                g.setComposite(AlphaComposite.SrcOver.derive(.94f));
                g.setColor(panel);
                g.fillRoundRect(x, y, width, height, 11, 11);
                g.setColor(line);
                g.drawRoundRect(x, y, width - 1, height - 1, 11, 11);
                g.drawImage(image, x + 7, y + 4, contentWidth, contentHeight, null);

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
        this(tabs, select, commit, move, (host, point) -> false, point -> null);
    }

    TabDragController(JTabbedPane tabs, Consumer<Component> select, BiConsumer<Component, Integer> commit,
                      BiConsumer<Component, Integer> move, java.util.function.BiPredicate<Component, Point> externalDrop) {
        this(tabs, select, commit, move, externalDrop, point -> null);
    }

    TabDragController(JTabbedPane tabs, Consumer<Component> select, BiConsumer<Component, Integer> commit,
                      BiConsumer<Component, Integer> move, java.util.function.BiPredicate<Component, Point> externalDrop,
                      Function<Point, Rectangle> dropPreview) {
        this.tabs = tabs; this.select = select; this.commit = commit; this.move = move;
        this.externalDrop = externalDrop;
        this.dropPreview = dropPreview;
        overlay.setOpaque(false);
        install(tabs);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape);
    }

    void install(Component component) {
        component.addMouseListener(this);
        component.addMouseMotionListener(this);
    }

    void uninstall(Component component) {
        component.removeMouseListener(this);
        component.removeMouseMotionListener(this);
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
        Rectangle tabBounds = tabs.getBoundsAt(index);
        Rectangle headerBounds = header == null || header.getParent() == null ? new Rectangle(tabBounds) :
                SwingUtilities.convertRectangle(header.getParent(), header.getBounds(), tabs);
        grabOffsetX = point.x - headerBounds.x;
        grabOffsetY = point.y - headerBounds.y;
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
            Rectangle tabBounds = new Rectangle(strip);
            Rectangle headerBounds = header == null || header.getParent() == null ? new Rectangle(tabBounds) :
                    SwingUtilities.convertRectangle(header.getParent(), header.getBounds(), tabs);
            double scale = tabs.getGraphicsConfiguration() == null ? 1
                    : tabs.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            ghostScale = scale;
            int ghostWidth = header == null ? tabBounds.width : Math.max(1, header.getWidth());
            int ghostHeight = header == null ? tabBounds.height : Math.max(1, header.getHeight());
            image = new BufferedImage((int) Math.ceil(ghostWidth * scale),
                    (int) Math.ceil(ghostHeight * scale), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.scale(scale, scale);
                if (header instanceof JComponent component && header.getParent() != null) component.paint(g);
                else { g.translate(-headerBounds.x, -headerBounds.y); tabs.paint(g); }
            } finally { g.dispose(); }
            layeredPane = tabs.getRootPane().getLayeredPane();
            overlay.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
            layeredPane.add(overlay, JLayeredPane.DRAG_LAYER);
            dragging = true;
        }
        pointer = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), layeredPane);
        preview = dropPreview.apply(pointer);

        // Reorder only while the pointer remains over this source tab strip; leaving it enters drop targeting.
        if (point.x >= 0 && point.x <= tabs.getWidth()
                && point.y >= strip.y - 3 && point.y <= strip.y + strip.height + 3) {
            reorderAt(point);
        } else {
            overlay.repaint();
        }
        event.consume();
    }

    private void reorderAt(Point point) {
        int current = tabs.indexOfComponent(source);
        if (current < 0) { finish(); return; }
        Rectangle bounds = tabs.getBoundsAt(current);
        ghostX = Math.max(0, Math.min(point.x - grabOffsetX, tabs.getWidth() - bounds.width));
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
    }

    @Override public void mouseReleased(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event) || source == null) return;
        if (!dragging) { finish(); return; }
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), tabs);
        boolean inSourceStrip = point.x >= 0 && point.x <= tabs.getWidth()
                && point.y >= strip.y - 3 && point.y <= strip.y + strip.height + 3;
        Point rootPoint = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), layeredPane);
        pointer = rootPoint;
        if (!inSourceStrip) {
            if (externalDrop.test(source, rootPoint)) finish(); else cancel();
            event.consume();
            return;
        }
        int index = tabs.indexOfComponent(source);
        if (index < 0) { finish(); return; }
        if (index != originalIndex) commit.accept(source, index);
        Rectangle targetBounds = SwingUtilities.convertRectangle(tabs, tabs.getBoundsAt(index), layeredPane);
        settlePointerFrom = new Point(pointer);
        settlePointerTo = new Point(targetBounds.x + grabOffsetX, targetBounds.y + grabOffsetY);
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
        if (settling) {
            ghostX = (float) (settleTo + (settleFrom - settleTo) * remaining);
            pointer = new Point(
                    (int) Math.round(settlePointerTo.x + (settlePointerFrom.x - settlePointerTo.x) * remaining),
                    (int) Math.round(settlePointerTo.y + (settlePointerFrom.y - settlePointerTo.y) * remaining));
        }
        if (strip != null) tabs.repaint(strip);
        if (layeredPane != null) overlay.repaint();
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
        source = null; header = null; image = null; strip = null; pointer = null; preview = null; layeredPane = null;
        settlePointerFrom = null; settlePointerTo = null;
        ghostScale = 1; grabOffsetX = 0; grabOffsetY = 0;
        originalOrder.clear();
        dragging = false; settling = false;
    }

    @Override public void close() {
        finish();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape);
    }
}
