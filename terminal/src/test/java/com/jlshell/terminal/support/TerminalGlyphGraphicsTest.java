package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class TerminalGlyphGraphicsTest {
    @Test void batchedGlyphsKeepPixelsColorsFontStylesAndClipping() {
        for(int style:new int[]{Font.PLAIN,Font.BOLD,Font.ITALIC})for(double scale:new double[]{1,1.5,2})
            for(Object aa:new Object[]{RenderingHints.VALUE_TEXT_ANTIALIAS_ON,RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB})
                for(Object fractional:new Object[]{RenderingHints.VALUE_FRACTIONALMETRICS_ON,RenderingHints.VALUE_FRACTIONALMETRICS_OFF}) {
                var expected=render(false,style,scale,aa,fractional);var actual=render(true,style,scale,aa,fractional);
                int different=0;
                for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++)
                    if(expected.getRGB(x,y)!=actual.getRGB(x,y))different++;
                assertEquals(0,different,"Glyph batching changed pixels: style="+style+" scale="+scale+" aa="+aa+" fractional="+fractional);
            }
    }
    private static BufferedImage render(boolean batch,int style,double scale,Object aa,Object fractional) {
        var image=new BufferedImage(1100,140,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();graphics.scale(scale,scale);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,aa);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,fractional);
        graphics.setColor(Color.BLACK);graphics.fillRect(0,0,1100,140);
        if(batch)graphics=new TerminalGlyphGraphics(graphics,new TerminalGlyphGraphics.LayoutCache());
        try {
            Font font=new Font("Monospaced",style,14);
            char[] text="2026 INFO [worker-1] value=123.5 ERROR WARN \u4e2d\u6587".toCharArray();
            int x=5;
            for(int i=0;i<text.length;i++){
                graphics.setFont(font);graphics.setColor(i>=10&&i<14?Color.CYAN:Color.WHITE);
                graphics.setClip(x,0,700-x,70);graphics.drawChars(text,i,1,x,30);x+=9;
            }
            graphics.setClip(null);graphics.setColor(Color.RED);graphics.fillRect(5,40,30,5);
        }finally{graphics.dispose();}
        return image;
    }
}
