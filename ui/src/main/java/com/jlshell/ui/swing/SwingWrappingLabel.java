package com.jlshell.ui.swing;

import javax.swing.*;
import javax.swing.text.View;
import java.awt.*;

/** Read-only wrapping text whose height follows the actual available width, rather than column count. */
final class SwingWrappingLabel extends JTextArea {
    SwingWrappingLabel(String text){
        super(text);setEditable(false);setFocusable(false);setLineWrap(true);setWrapStyleWord(true);setOpaque(false);setBorder(null);
    }
    @Override public Dimension getMinimumSize(){return new Dimension(0,getFontMetrics(getFont()).getHeight());}
    @Override public Dimension getPreferredSize(){
        if(getUI()==null)return super.getPreferredSize();
        int width=getWidth();
        if(width<=1&&getParent()!=null){Insets insets=getParent().getInsets();width=getParent().getWidth()-insets.left-insets.right;}
        if(width<=1)width=360;
        Insets insets=getInsets();View root=getUI().getRootView(this);root.setSize(Math.max(1,width-insets.left-insets.right),Integer.MAX_VALUE);
        return new Dimension(Math.min(width,360),(int)Math.ceil(root.getPreferredSpan(View.Y_AXIS))+insets.top+insets.bottom);
    }
}
