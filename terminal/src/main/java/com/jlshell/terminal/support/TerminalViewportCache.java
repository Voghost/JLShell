package com.jlshell.terminal.support;

import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.Rectangle;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.function.Consumer;
import javax.swing.Timer;

/**
 * Swing viewport-style scroll blitting: move existing pixels, paint only the exposed strip.
 * One temporary device-resolution surface per scrolling terminal, released when input stops.
 */
final class TerminalViewportCache {
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Timer release = new Timer(250, event -> clear());
    private Image surface;
    private GraphicsConfiguration configuration;
    private long revision = -1;
    private int position, width, height;
    private double scale;
    private long fullFrames, blitFrames;

    TerminalViewportCache() { release.setRepeats(false); }

    boolean paint(Graphics2D target, int width, int height, int position, long revision,
                  Consumer<Graphics2D> painter) {
        return paint(target, width, height, position, revision, 0, painter);
    }

    boolean paint(Graphics2D target, int width, int height, int position, long revision,
                  int repaintMargin, Consumer<Graphics2D> painter) {
        var transform = target.getTransform();
        double scale = transform.getScaleX();
        // A fractional device phase needs a fresh rasterization, not a resampled bitmap.
        if (!AlphaComposite.SrcOver.equals(target.getComposite())
                || scale < 1 || scale != Math.rint(scale) || transform.getScaleY() != scale
                || transform.getShearX() != 0 || transform.getShearY() != 0
                || transform.getTranslateX() != Math.rint(transform.getTranslateX())
                || transform.getTranslateY() != Math.rint(transform.getTranslateY())
                || width <= 0 || height <= 0
                || width * scale * height * scale * 4 > MAX_BYTES) return false;
        var configuration = target.getDeviceConfiguration();
        if (surface instanceof VolatileImage accelerated) {
            int status = accelerated.validate(configuration);
            if (status == VolatileImage.IMAGE_INCOMPATIBLE) clear();
            else if (status == VolatileImage.IMAGE_RESTORED) this.revision = -1;
        }
        if (surface == null || this.width != width || this.height != height || this.scale != scale
                || this.configuration != configuration) {
            clear();
            // Keep the blit on the screen pipeline (Metal/D3D). Uploading an entire CPU bitmap
            // on every trackpad event is slower than drawing text on a real Retina window.
            surface = configuration != null && configuration.getDevice().getType() == GraphicsDevice.TYPE_RASTER_SCREEN
                    && configuration.getDefaultTransform().getScaleX() == scale
                    && configuration.getDefaultTransform().getScaleY() == scale
                    ? configuration.createCompatibleVolatileImage(width, height, Transparency.OPAQUE)
                    : new BufferedImage((int)(width * scale), (int)(height * scale), BufferedImage.TYPE_INT_RGB);
            this.configuration = configuration;
            this.width = width; this.height = height; this.scale = scale;
        }
        int delta = position - this.position;
        Rectangle dirty = new Rectangle(0, 0, width, height);
        if (this.revision == revision && Math.abs((long)delta) < height) {
            blitFrames++;
            if (delta == 0) dirty.height = 0;
            else {
                Graphics2D pixels = surfaceGraphics();
                try { pixels.copyArea(0, 0, width, height, 0, -delta); }
                finally { pixels.dispose(); }
                dirty = new Rectangle(0, delta > 0 ? height - delta : 0, width, Math.abs(delta));
                // Repaint the neighbouring rows too. A glyph can straddle the exposed strip;
                // preserving its other half through multiple GPU copies can leave seams.
                int top = Math.max(0, dirty.y - repaintMargin);
                int bottom = Math.min(height, dirty.y + dirty.height + repaintMargin);
                dirty = new Rectangle(0, top, width, bottom - top);
            }
        } else fullFrames++;
        if (!dirty.isEmpty()) {
            Graphics2D graphics = surfaceGraphics();
            try {
                graphics.setRenderingHints(target.getRenderingHints());
                // JediTerm changes its clip per cell; enforce the exposed strip through every reset.
                if (dirty.height == height) painter.accept(graphics);
                else painter.accept(new HighlightGraphics(graphics, (x, y, color) -> null, dirty));
            } finally { graphics.dispose(); }
        }
        this.position = position; this.revision = revision;
        target.drawImage(surface, surface instanceof VolatileImage ? new AffineTransform()
                : AffineTransform.getScaleInstance(1 / scale, 1 / scale), null);
        if (surface instanceof VolatileImage accelerated && accelerated.contentsLost()) {
            clear();
            return false;
        }
        release.restart();
        return true;
    }

    void clear() {
        release.stop();
        if (surface != null) surface.flush();
        surface = null; revision = -1;
    }

    private Graphics2D surfaceGraphics() {
        Graphics2D graphics = surface instanceof VolatileImage accelerated
                ? accelerated.createGraphics() : ((BufferedImage)surface).createGraphics();
        // A screen-compatible image already has the monitor's default transform (2x on Retina).
        graphics.setTransform(AffineTransform.getScaleInstance(scale, scale));
        return graphics;
    }

    long bytes() { return surface == null ? 0 : (long)(width * scale) * (long)(height * scale) * 4; }
    long fullFrames() { return fullFrames; }
    long blitFrames() { return blitFrames; }
}
