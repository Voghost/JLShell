package com.jlshell.terminal.support;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.JediTerminal;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import org.junit.jupiter.api.Test;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TerminalSmoothScrollingTest {
    @Test void paintsSubLineMotionWithoutBlankEdgesAndPreservesBufferAndScrollbarControl() throws Exception {
        SwingExecutors.runOnEdtAsync(()->{
            var settings=new JlshellSettingsProvider(new FontProfile("Monospaced",16,false,1),TerminalColorScheme.dark());
            var style=new StyleState(); style.setDefaultStyle(settings.defaultTextStyle());
            var buffer=new TerminalTextBuffer(60,12,style);
            var panel=new Probe(settings,buffer,style);
            var bar=new javax.swing.JScrollBar(); panel.init(bar);
            panel.setSize(600,panel.cellHeight()*12);
            var terminal=new JediTerminal(panel,buffer,style);panel.setCoordAccessor(terminal);
            try {
                for(int i=0;i<100;i++) { terminal.writeCharacters(String.format("row %03d INFO record %03d",i,i));terminal.carriageReturn();terminal.newLine(); }
                paint(panel); // publish the history range to the scrollbar model
                String history=buffer.getScreenLines(); int historyCount=buffer.getHistoryLinesCount();
                panel.getVerticalScrollModel().setValue(-1); BufferedImage aligned=paint(panel);
                panel.getVerticalScrollModel().setValue(0); BufferedImage following=paint(panel);
                MouseWheelEvent event=wheel(panel,-.25);
                panel.handleMouseWheelEvent(event,bar);panel.settle();paint(panel);assertTrue(event.isConsumed());
                assertEquals(-1,panel.getVerticalScrollModel().getValue());
                java.util.concurrent.atomic.AtomicBoolean pending;
                try {
                    var field=com.jediterm.terminal.ui.TerminalPanel.class.getDeclaredField("needRepaint"); field.setAccessible(true);
                    pending=(java.util.concurrent.atomic.AtomicBoolean)field.get(panel);
                } catch(ReflectiveOperationException ex) {throw new AssertionError(ex);}
                pending.set(false);
                BufferedImage smooth=paint(panel);
                assertFalse(pending.get(),"Fractional edge painting must not request another frame when scrolling is idle");
                int pixels=Math.max(1,(int)Math.round(panel.cellHeight()*.25));
                for(int y=0;y<panel.getHeight()-panel.cellHeight();y++) for(int x=0;x<250;x++)
                    assertEquals(aligned.getRGB(x,y+pixels),smooth.getRGB(x,y),"Viewport must move in pixels, not jump a whole row");
                for(int y=panel.getHeight()-pixels;y<panel.getHeight();y++) for(int x=0;x<250;x++)
                    assertEquals(following.getRGB(x,y-(panel.cellHeight()-pixels)),smooth.getRGB(x,y),"Exposed bottom edge must contain the next row");
                assertEquals(-1,panel.getVerticalScrollModel().getValue(),"Extra-row painting must restore the real scrollbar");
                assertEquals(history,buffer.getScreenLines());assertEquals(historyCount,buffer.getHistoryLinesCount());
                int y=panel.cellHeight()-pixels+1;
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,java.awt.event.MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),0,10,y,1,false,java.awt.event.MouseEvent.BUTTON1));
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,java.awt.event.MouseEvent.MOUSE_DRAGGED,
                        System.currentTimeMillis(),java.awt.event.MouseEvent.BUTTON1_DOWN_MASK,100,y,0,false,java.awt.event.MouseEvent.NOBUTTON));
                assertNotNull(panel.getSelection());
                assertEquals(0,panel.getSelection().getStart().y,"Mouse selection must use the same fractional offset as painting");
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,java.awt.event.MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(),0,100,y,1,false,java.awt.event.MouseEvent.BUTTON1));
                // Fine input continues accumulating even after the native range remains on the same row.
                panel.handleMouseWheelEvent(wheel(panel,-.1),bar);panel.settle();paint(panel);
                panel.getVerticalScrollModel().setValue(-8); BufferedImage manual=paint(panel);
                assertEquals(-8,panel.getVerticalScrollModel().getValue());
                assertEquals(manual.getRGB(12,10),paint(panel).getRGB(12,10));
                panel.getVerticalScrollModel().setValue(0);BufferedImage beforeTouch=paint(panel);
                panel.handleMouseWheelEvent(phase(panel,2,0),bar);
                MouseWheelEvent touch=phase(panel,3,-1);panel.handleMouseWheelEvent(touch,bar);panel.settle();paint(panel);assertTrue(touch.isConsumed());
                assertEquals(-1,panel.getVerticalScrollModel().getValue(),"Native touch updates are pixels even when their rotation is an integer");
                BufferedImage touched=paint(panel);
                for(int row=3;row<panel.getHeight()-panel.cellHeight();row++)for(int x=0;x<250;x++)
                    assertEquals(beforeTouch.getRGB(x,row-3),touched.getRGB(x,row),"Native update must settle at three pixels after the short interpolation");
                panel.handleMouseWheelEvent(phase(panel,4,0),bar);
                terminal.useAlternateBuffer(true);paint(panel);
                int before=panel.getVerticalScrollModel().getValue();
                int immediate=Math.max(panel.getVerticalScrollModel().getMinimum(),before-3);
                panel.handleMouseWheelEvent(wheel(panel,-1),bar);
                assertEquals(immediate,panel.getVerticalScrollModel().getValue(),"Alternate-screen wheel handling must remain immediate, without animation");
            } finally {panel.dispose();}
        }).get(15,TimeUnit.SECONDS);
    }
    private static MouseWheelEvent phase(Probe panel,int phase,double rotation){
        return new MouseWheelEvent(panel,java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,10,10,0,false,phase,3,(int)Math.signum(rotation),rotation);
    }
    private static MouseWheelEvent wheel(Probe panel,double rotation) {
        return new MouseWheelEvent(panel,java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,
                10,10,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,(int)Math.signum(rotation),rotation);
    }
    private static BufferedImage paint(Probe panel) {
        var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();try{panel.paintComponent(g);}finally{g.dispose();}return image;
    }
    private static class Probe extends RefreshableTerminalPanel {
        private final java.util.concurrent.atomic.AtomicLong clock;
        Probe(JlshellSettingsProvider settings,TerminalTextBuffer buffer,StyleState style){this(settings,buffer,style,new java.util.concurrent.atomic.AtomicLong(1_000_000_000L));}
        private Probe(JlshellSettingsProvider settings,TerminalTextBuffer buffer,StyleState style,java.util.concurrent.atomic.AtomicLong clock){
            super(settings,buffer,style,settings,key->key,new TerminalWheelInput(false),clock::get);this.clock=clock;
        }
        void settle(){clock.addAndGet(TerminalScrollAnimation.PRECISE_DELAY_NANOS);}
        int cellHeight(){return myCharSize.height;}
    }
}
