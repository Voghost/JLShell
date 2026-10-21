package com.jlshell.ui.swing;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Single brand master shared by native views; paint the full-resolution image for HiDPI. */
public final class SwingBrandIcon implements Icon {
    private static final BufferedImage IMAGE=load();
    private final int size;
    public SwingBrandIcon(int size){this.size=size;}
    private static BufferedImage load(){
        try(var stream=SwingBrandIcon.class.getResourceAsStream("/icons/brand/jlshell.png")){
            if(stream==null)throw new IllegalStateException("Missing JLShell brand master");
            return ImageIO.read(stream);
        }catch(IOException error){throw new IllegalStateException("Cannot load JLShell brand master",error);}
    }
    public int getIconWidth(){return size;}public int getIconHeight(){return size;}
    public void paintIcon(Component component,Graphics graphics,int x,int y){
        Graphics2D g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(IMAGE,x,y,size,size,null);
        }finally{g.dispose();}
    }
}
