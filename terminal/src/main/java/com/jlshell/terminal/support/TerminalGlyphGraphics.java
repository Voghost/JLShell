package com.jlshell.terminal.support;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.util.Arrays;

/** Batches simple glyphs at their exact terminal-cell positions, like an editor text fragment. */
final class TerminalGlyphGraphics extends HighlightGraphics {
    private final char[] text = new char[256];
    private final int[] positions = new int[256];
    private int length, baseline;
    private Shape runClip;
    private final LayoutCache cache;
    private byte[] glyphSafety;
    private boolean batchCompatible;
    private int requestedClipY,requestedClipHeight,runClipY,runClipHeight;
    private boolean clipEmpty;
    private int ascent,descent;
    private final Rectangle clipBounds=new Rectangle();
    static final class LayoutCache {
        private record Key(Font font,java.awt.font.FontRenderContext context,String text,int step) {}
        private final java.util.LinkedHashMap<Key,GlyphVector> entries=new java.util.LinkedHashMap<>(64,.75f,true);
        private int glyphCount;
        private record FontContext(Font font,java.awt.font.FontRenderContext context) {}
        private final java.util.LinkedHashMap<FontContext,byte[]> safeCharacters=new java.util.LinkedHashMap<>(16,.75f,true);
        byte[] safety(Graphics2D graphics){
            var key=new FontContext(graphics.getFont(),graphics.getFontRenderContext());
            byte[] flags=safeCharacters.computeIfAbsent(key,k->new byte[128]);
            if(safeCharacters.size()>16)safeCharacters.remove(safeCharacters.keySet().iterator().next());
            return flags;
        }
        GlyphVector layout(Graphics2D graphics,char[] text,int length,int[] positions) {
            int step=length>1?positions[1]-positions[0]:0;
            boolean regular=true;for(int i=2;i<length;i++)if(positions[i]-positions[i-1]!=step){regular=false;break;}
            Key key=regular?new Key(graphics.getFont(),graphics.getFontRenderContext(),new String(text,0,length),step):null;
            GlyphVector vector=key==null?null:entries.get(key);
            if(vector!=null)return vector;
            vector=graphics.getFont().createGlyphVector(graphics.getFontRenderContext(),Arrays.copyOf(text,length));
            for(int i=0;i<length;i++)vector.setGlyphPosition(i,new Point2D.Float(positions[i]-positions[0],0));
            if(key!=null){entries.put(key,vector);glyphCount+=length;
                var iterator=entries.entrySet().iterator();
                while(entries.size()>128||glyphCount>16_384){var entry=iterator.next();glyphCount-=entry.getKey().text.length();iterator.remove();}
            }
            return vector;
        }
        void clear(){entries.clear();safeCharacters.clear();glyphCount=0;}
    }
    TerminalGlyphGraphics(Graphics2D graphics,LayoutCache cache) {
        super(graphics,(x,y,color)->null);this.cache=cache;
        var metrics=graphics.getFontMetrics();ascent=metrics.getMaxAscent();descent=metrics.getMaxDescent();
    }

    @Override public void drawChars(char[] data, int offset, int count, int x, int y) {
        if (clipEmpty || clipBounds.height>0 && (y+descent+2<clipBounds.y || y-ascent-2>clipBounds.y+clipBounds.height)) return;
        if (count != 1 || data[offset] < 32 || data[offset] >= 127 || delegate.getFont().isItalic() || !safeToBatch(data[offset])) {
            flush(); delegate.drawChars(data,offset,count,x,y); return;
        }
        if (length > 0 && (baseline != y || length == text.length || x <= positions[length-1])) flush();
        if (length == 0) { baseline=y; runClip=delegate.getClip();runClipY=requestedClipY;runClipHeight=requestedClipHeight; }
        text[length]=data[offset];positions[length]=x;length++;
    }
    void flush() {
        if (length == 0) return;
        Shape latest=delegate.getClip();delegate.setClip(runClip);
        if (length == 1) delegate.drawChars(text,0,1,positions[0],baseline);
        else {
            GlyphVector glyphs=cache.layout(delegate,text,length,positions);
            delegate.drawGlyphVector(glyphs,positions[0],baseline);
        }
        delegate.setClip(latest);length=0;
    }
    private boolean safeToBatch(char character){
        if(character==' ')return true;
        if(glyphSafety==null){
            glyphSafety=cache.safety(delegate);
            var context=delegate.getFontRenderContext();var transform=context.getTransform();
            boolean fractionalScale=transform.getScaleX()!=Math.rint(transform.getScaleX())||transform.getScaleY()!=Math.rint(transform.getScaleY());
            Object aa=context.getAntiAliasingHint();
            boolean lcd=aa==RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB||aa==RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR
                    ||aa==RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB||aa==RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR;
            // Some Java2D LCD pipelines rasterize string and vector glyphs differently at fractional DPI.
            // Keep that combination on the original renderer; the allocation/clip fast paths still apply.
            batchCompatible=!(fractionalScale&&lcd);
        }
        if(!batchCompatible)return false;
        if(glyphSafety[character]==0){
            var vector=delegate.getFont().createGlyphVector(delegate.getFontRenderContext(),new char[]{character});
            // JediTerm clips each glyph's negative left bearing. Keep those glyphs on its original path.
            var context=delegate.getFontRenderContext();var transform=context.getTransform();
            int phases=transform.getScaleX()==Math.rint(transform.getScaleX())&&transform.getScaleY()==Math.rint(transform.getScaleY())?1:4;
            boolean safe=true;
            for(int x=0;x<phases&&safe;x++)for(int y=0;y<phases&&safe;y++)
                safe=vector.getGlyphPixelBounds(0,context,(float)(x/(4*transform.getScaleX())),(float)(y/(4*transform.getScaleY()))).x>=1;
            glyphSafety[character]=(byte)(safe?1:-1);
        }
        return glyphSafety[character]>0;
    }
    @Override public void setClip(Shape clip) {
        if(clip instanceof Rectangle rectangle){setClip(rectangle.x,rectangle.y,rectangle.width,rectangle.height);return;}
        flush();delegate.setClip(clip);
        Rectangle bounds=delegate.getClipBounds(clipBounds);clipEmpty=bounds!=null&&bounds.isEmpty();
    }
    @Override public void setClip(int x,int y,int width,int height) {
        boolean rowChanged=y!=requestedClipY||height!=requestedClipHeight;
        if(length>0&&(y!=runClipY||height!=runClipHeight))flush();
        delegate.setClip(x,y,width,height);requestedClipY=y;requestedClipHeight=height;
        if(rowChanged){Rectangle bounds=delegate.getClipBounds(clipBounds);clipEmpty=bounds!=null&&bounds.isEmpty();}
    }
    @Override public void setColor(Color color) { if(!color.equals(delegate.getColor()))flush();delegate.setColor(color); }
    @Override public void setFont(Font font) { if(!font.equals(delegate.getFont())){flush();glyphSafety=null;var metrics=delegate.getFontMetrics(font);ascent=metrics.getMaxAscent();descent=metrics.getMaxDescent();}delegate.setFont(font); }
    @Override public void setPaint(Paint paint) { flush();delegate.setPaint(paint); }
    @Override public void setComposite(Composite composite) { flush();delegate.setComposite(composite); }
    @Override public void fillRect(int x,int y,int width,int height) { flush();delegate.fillRect(x,y,width,height); }
    @Override public void drawLine(int x1,int y1,int x2,int y2) { flush();delegate.drawLine(x1,y1,x2,y2); }
    @Override public void fill(Shape shape) { flush();delegate.fill(shape); }
    @Override public void draw(Shape shape) { flush();delegate.draw(shape); }
    @Override public void drawString(String text,int x,int y) { flush();delegate.drawString(text,x,y); }
    @Override public void drawString(String text,float x,float y) { flush();delegate.drawString(text,x,y); }
    @Override public Graphics create() { flush();return new TerminalGlyphGraphics((Graphics2D)delegate.create(),cache); }
    @Override public void dispose() { flush();delegate.dispose(); }
}
