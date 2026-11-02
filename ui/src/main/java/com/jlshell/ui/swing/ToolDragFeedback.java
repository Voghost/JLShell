package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;

/** IDEA separates the stripe icon drag image from the one-line destination tooltip. */
final class ToolDragFeedback implements AutoCloseable {
    private final Window owner;
    private JWindow window, tooltip;
    private JLabel title, hint;
    private java.util.function.Consumer<java.awt.event.MouseEvent> forwarder;
    void setEventForwarder(java.util.function.Consumer<java.awt.event.MouseEvent> forwarder){this.forwarder=forwarder;}
    ToolDragFeedback(Window owner) { this.owner=owner; }

    void show(Point pointer,String name,Icon icon,String destination,boolean targetOnLeft) {
        if(window==null) {
            window=popup("workspace.toolDragFeedback"); tooltip=popup("workspace.toolDragHint");
            title=new JLabel(); title.setName("workspace.dragToolName");
            title.setHorizontalAlignment(SwingConstants.CENTER);title.setPreferredSize(new Dimension(28,28));
            JPanel imageSurface=new DragSurface(true);imageSurface.add(title);window.setContentPane(imageSurface);
            hint=new JLabel();hint.setName("workspace.dragDestination");
            hint.setBorder(BorderFactory.createEmptyBorder(7,10,7,10));
            hint.setForeground(UIManager.getColor("Label.foreground"));
            JPanel hintSurface=new DragSurface(false);hintSurface.add(hint);tooltip.setContentPane(hintSurface);
            java.awt.event.MouseAdapter forwarding=new java.awt.event.MouseAdapter(){
                public void mouseDragged(java.awt.event.MouseEvent event){if(forwarder!=null)forwarder.accept(event);}
                public void mouseReleased(java.awt.event.MouseEvent event){if(forwarder!=null)forwarder.accept(event);}
            };
            title.addMouseMotionListener(forwarding);title.addMouseListener(forwarding);
            hint.addMouseMotionListener(forwarding);hint.addMouseListener(forwarding);
            if(window.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT))
                window.setOpacity(.85f);
        }
        title.setIcon(icon);title.getAccessibleContext().setAccessibleName(name);hint.setText(destination);
        window.pack();tooltip.pack();
        Rectangle screen=screenAt(pointer);
        int x=bounded(pointer.x-7,screen.x,screen.x+screen.width-window.getWidth());
        int y=bounded(pointer.y-7,screen.y,screen.y+screen.height-window.getHeight());
        window.setLocation(x,y);window.setVisible(true);
        int hintX=targetOnLeft?x+window.getWidth()+10:x-tooltip.getWidth()-10;
        tooltip.setLocation(bounded(hintX,screen.x,screen.x+screen.width-tooltip.getWidth()),
                bounded(y+(window.getHeight()-tooltip.getHeight())/2,screen.y,screen.y+screen.height-tooltip.getHeight()));
        tooltip.setVisible(true);
    }
    private JWindow popup(String name) {
        JWindow popup=new JWindow(owner);popup.setName(name);popup.setFocusableWindowState(false);popup.setAutoRequestFocus(false);
        boolean transparent=popup.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        popup.setBackground(transparent?new Color(0,0,0,0):SwingTheme.color("panel"));
        popup.getRootPane().setOpaque(false);popup.getLayeredPane().setOpaque(false);return popup;
    }
    private static final class DragSurface extends JPanel {
        private final boolean image;
        DragSurface(boolean image){super(new BorderLayout());this.image=image;setOpaque(false);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();
            try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color("panel"));g.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                if(image){g.setColor(SwingTheme.color("headerPressed"));g.fillRoundRect(0,0,getWidth(),getHeight(),8,8);}
                else {g.setColor(SwingTheme.color("border"));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);}
            }finally{g.dispose();}
        }
    }
    private static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private static Rectangle screenAt(Point point) {
        for(var device:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
            Rectangle bounds=device.getDefaultConfiguration().getBounds();if(bounds.contains(point))return bounds;
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }
    boolean isShowing(){return window!=null&&window.isShowing();}
    @Override public void close(){if(window!=null)window.dispose();if(tooltip!=null)tooltip.dispose();window=tooltip=null;}
}
