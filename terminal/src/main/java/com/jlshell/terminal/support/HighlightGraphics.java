package com.jlshell.terminal.support;

/** Delegates terminal painting unchanged, substituting only glyph foreground colors. */
class HighlightGraphics extends java.awt.Graphics2D {
    interface Foreground { java.awt.Color at(int x, int baseline, java.awt.Color original); }
    protected final java.awt.Graphics2D delegate;
    private final Foreground foreground;
    private final java.awt.Shape clipLimit;
    HighlightGraphics(java.awt.Graphics2D delegate, Foreground foreground) { this(delegate, foreground, null); }
    HighlightGraphics(java.awt.Graphics2D delegate, Foreground foreground, java.awt.Shape clipLimit) {
        this.delegate = delegate; this.foreground = foreground; this.clipLimit = clipLimit instanceof java.awt.geom.Area area && area.isRectangular() ? area.getBounds() : clipLimit;
        if (clipLimit != null) setClip(clipLimit);
    }
    @Override public void drawChars(char[] data, int offset, int length, int x, int y) {
        java.awt.Color old = delegate.getColor();
        java.awt.Color next = foreground.at(x, y, old);
        if (next != null) delegate.setColor(next);
        delegate.drawChars(data, offset, length, x, y);
        delegate.setColor(old);
    }
    @Override public void translate(int p0, int p1) { delegate.translate(p0, p1); }
    @Override public void translate(double p0, double p1) { delegate.translate(p0, p1); }
    @Override public java.awt.Paint getPaint() { return delegate.getPaint(); }
    @Override public void setPaint(java.awt.Paint p0) { delegate.setPaint(p0); }
    @Override public void draw(java.awt.Shape p0) { delegate.draw(p0); }
    @Override public boolean drawImage(java.awt.Image p0, java.awt.geom.AffineTransform p1, java.awt.image.ImageObserver p2) { return delegate.drawImage(p0, p1, p2); }
    @Override public void drawImage(java.awt.image.BufferedImage p0, java.awt.image.BufferedImageOp p1, int p2, int p3) { delegate.drawImage(p0, p1, p2, p3); }
    @Override public void drawRenderedImage(java.awt.image.RenderedImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderedImage(p0, p1); }
    @Override public void drawRenderableImage(java.awt.image.renderable.RenderableImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderableImage(p0, p1); }
    @Override public void drawString(java.text.AttributedCharacterIterator p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.lang.String p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.text.AttributedCharacterIterator p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.lang.String p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawGlyphVector(java.awt.font.GlyphVector p0, float p1, float p2) { delegate.drawGlyphVector(p0, p1, p2); }
    @Override public java.awt.GraphicsConfiguration getDeviceConfiguration() { return delegate.getDeviceConfiguration(); }
    @Override public void setComposite(java.awt.Composite p0) { delegate.setComposite(p0); }
    @Override public void setStroke(java.awt.Stroke p0) { delegate.setStroke(p0); }
    @Override public void setRenderingHint(java.awt.RenderingHints.Key p0, java.lang.Object p1) { delegate.setRenderingHint(p0, p1); }
    @Override public java.lang.Object getRenderingHint(java.awt.RenderingHints.Key p0) { return delegate.getRenderingHint(p0); }
    @Override public void setRenderingHints(java.util.Map p0) { delegate.setRenderingHints(p0); }
    @Override public void addRenderingHints(java.util.Map p0) { delegate.addRenderingHints(p0); }
    @Override public java.awt.RenderingHints getRenderingHints() { return delegate.getRenderingHints(); }
    @Override public void shear(double p0, double p1) { delegate.shear(p0, p1); }
    @Override public void setTransform(java.awt.geom.AffineTransform p0) { delegate.setTransform(p0); }
    @Override public java.awt.geom.AffineTransform getTransform() { return delegate.getTransform(); }
    @Override public java.awt.Composite getComposite() { return delegate.getComposite(); }
    @Override public void setBackground(java.awt.Color p0) { delegate.setBackground(p0); }
    @Override public java.awt.Color getBackground() { return delegate.getBackground(); }
    @Override public java.awt.Stroke getStroke() { return delegate.getStroke(); }
    @Override public void clip(java.awt.Shape p0) { delegate.clip(p0); }
    @Override public java.awt.font.FontRenderContext getFontRenderContext() { return delegate.getFontRenderContext(); }
    @Override public void scale(double p0, double p1) { delegate.scale(p0, p1); }
    @Override public void fill(java.awt.Shape p0) { delegate.fill(p0); }
    @Override public void transform(java.awt.geom.AffineTransform p0) { delegate.transform(p0); }
    @Override public void rotate(double p0, double p1, double p2) { delegate.rotate(p0, p1, p2); }
    @Override public void rotate(double p0) { delegate.rotate(p0); }
    @Override public boolean hit(java.awt.Rectangle p0, java.awt.Shape p1, boolean p2) { return delegate.hit(p0, p1, p2); }
    @Override public java.awt.Color getColor() { return delegate.getColor(); }
    @Override public void fillRect(int p0, int p1, int p2, int p3) { delegate.fillRect(p0, p1, p2, p3); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.image.ImageObserver p9) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.image.ImageObserver p3) { return delegate.drawImage(p0, p1, p2, p3); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.Color p9, java.awt.image.ImageObserver p10) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.Color p5, java.awt.image.ImageObserver p6) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.Color p3, java.awt.image.ImageObserver p4) { return delegate.drawImage(p0, p1, p2, p3, p4); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.image.ImageObserver p5) { return delegate.drawImage(p0, p1, p2, p3, p4, p5); }
    @Override public void clipRect(int p0, int p1, int p2, int p3) { delegate.clipRect(p0, p1, p2, p3); }
    @Override public java.awt.Font getFont() { return delegate.getFont(); }
    @Override public java.awt.FontMetrics getFontMetrics(java.awt.Font p0) { return delegate.getFontMetrics(p0); }
    @Override public void drawLine(int p0, int p1, int p2, int p3) { delegate.drawLine(p0, p1, p2, p3); }
    @Override public void drawPolygon(int[] p0, int[] p1, int p2) { delegate.drawPolygon(p0, p1, p2); }
    @Override public void fillPolygon(int[] p0, int[] p1, int p2) { delegate.fillPolygon(p0, p1, p2); }
    @Override public java.awt.Rectangle getClipBounds() { return delegate.getClipBounds(); }
    @Override public java.awt.Rectangle getClipBounds(java.awt.Rectangle target) { return delegate.getClipBounds(target); }
    @Override public void setPaintMode() { delegate.setPaintMode(); }
    @Override public void setXORMode(java.awt.Color p0) { delegate.setXORMode(p0); }
    @Override public void setFont(java.awt.Font p0) { delegate.setFont(p0); }
    @Override public void setClip(java.awt.Shape shape) {
        if (clipLimit == null) delegate.setClip(shape);
        else if (clipLimit instanceof java.awt.Rectangle limit && (shape == null || shape instanceof java.awt.Rectangle)) {
            delegate.setClip(shape == null ? limit : limit.intersection((java.awt.Rectangle)shape));
        } else {
            java.awt.geom.Area area = new java.awt.geom.Area(clipLimit);
            if (shape != null) area.intersect(new java.awt.geom.Area(shape));
            delegate.setClip(area);
        }
    }
    @Override public void setClip(int x,int y,int width,int height) {
        if(clipLimit==null)delegate.setClip(x,y,width,height);
        else if(clipLimit instanceof java.awt.Rectangle limit){
            int left=Math.max(x,limit.x),top=Math.max(y,limit.y);
            delegate.setClip(left,top,Math.max(0,Math.min(x+width,limit.x+limit.width)-left),Math.max(0,Math.min(y+height,limit.y+limit.height)-top));
        }else setClip(new java.awt.Rectangle(x,y,width,height));
    }
    @Override public java.awt.Shape getClip() { return delegate.getClip(); }
    @Override public void copyArea(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.copyArea(p0, p1, p2, p3, p4, p5); }
    @Override public void clearRect(int p0, int p1, int p2, int p3) { delegate.clearRect(p0, p1, p2, p3); }
    @Override public void drawRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawRoundRect(p0, p1, p2, p3, p4, p5); }
    @Override public void fillRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillRoundRect(p0, p1, p2, p3, p4, p5); }
    @Override public void drawOval(int p0, int p1, int p2, int p3) { delegate.drawOval(p0, p1, p2, p3); }
    @Override public void fillOval(int p0, int p1, int p2, int p3) { delegate.fillOval(p0, p1, p2, p3); }
    @Override public void drawArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawArc(p0, p1, p2, p3, p4, p5); }
    @Override public void fillArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillArc(p0, p1, p2, p3, p4, p5); }
    @Override public void drawPolyline(int[] p0, int[] p1, int p2) { delegate.drawPolyline(p0, p1, p2); }
    @Override public java.awt.Graphics create() { return new HighlightGraphics((java.awt.Graphics2D)delegate.create(), foreground, clipLimit); }
    @Override public void setColor(java.awt.Color p0) { delegate.setColor(p0); }
    @Override public void dispose() { delegate.dispose(); }
}
