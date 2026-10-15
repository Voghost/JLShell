package com.jlshell.ui.swing;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.*;

/** GitHub uses the existing about-github.svg; other resource icons use clear 24px geometry. */
public record AboutLinkIcon(Kind kind,int size) implements Icon {
    public enum Kind { WEBSITE, GITHUB, DOWNLOAD, ISSUES }
    public int getIconWidth(){return size;} public int getIconHeight(){return size;}
    private static final Shape GITHUB=github();
    private static Shape github(){Path2D.Double p=new Path2D.Double();
        p.moveTo(511.6,76.3);
        p.curveTo(264.3,76.2,64.0,276.4,64.0,523.5);
        p.curveTo(64.0,718.9,189.3,885.0,363.8,946.0);
        p.curveTo(387.3,951.9,383.7,935.2,383.7,923.8);
        p.lineTo(383.7,846.3);
        p.curveTo(248.0,862.2,242.5,772.4,233.4,757.4);
        p.curveTo(215.0,726.0,171.5,718.0,184.5,703.0);
        p.curveTo(215.4,687.1,246.9,707.0,283.4,760.9);
        p.curveTo(309.8,800.0,361.3,793.4,387.4,786.9);
        p.curveTo(393.1,763.4,405.3,742.4,422.1,726.1);
        p.curveTo(281.5,700.9,222.9,615.1,222.9,513.1);
        p.curveTo(222.9,463.6,239.2,418.1,271.2,381.4);
        p.curveTo(250.8,320.9,273.1,269.1,276.1,261.4);
        p.curveTo(334.2,256.2,394.6,303,399.3,306.7);
        p.curveTo(432.3,297.8,470,293.1,512.2,293.1);
        p.curveTo(554.6,293.1,592.4,298.0,625.7,307.0);
        p.curveTo(637,298.4,693,258.2,747,263.1);
        p.curveTo(749.9,270.8,771.7,321.4,752.5,381.1);
        p.curveTo(784.9,417.9,801.4,463.8,801.4,513.4);
        p.curveTo(801.4,615.6,742.4,701.5,601.4,726.3);
        p.curveTo(624.9,749.5,639.5,781.7,639.5,817.3);
        p.lineTo(639.5,929.8);
        p.curveTo(640.3,938.8,639.5,947.7,654.5,947.7);
        p.curveTo(831.6,888.0,959.1,720.7,959.1,523.6);
        p.curveTo(959.1,276.4,758.7,76.3,511.6,76.3);
        p.closePath();
        return p;
    }
    public void paintIcon(Component component,Graphics graphics,int x,int y){
        Graphics2D g=(Graphics2D)graphics.create();try{
            g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.getForeground());
            if(kind==Kind.GITHUB){g.scale(size/1024.0,size/1024.0);g.fill(GITHUB);return;}
            g.scale(size/24.0,size/24.0);g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            switch(kind){
                case WEBSITE->{g.draw(new Ellipse2D.Double(2.5,2.5,19,19));g.draw(new Ellipse2D.Double(7,2.5,10,19));g.draw(new Line2D.Double(2.5,12,21.5,12));}
                case DOWNLOAD->{g.draw(new Line2D.Double(12,3,12,15));g.draw(new Line2D.Double(7,10,12,15));g.draw(new Line2D.Double(12,15,17,10));Path2D p=new Path2D.Double();p.moveTo(4,16);p.lineTo(4,21);p.lineTo(20,21);p.lineTo(20,16);g.draw(p);}
                case ISSUES->{Path2D p=new Path2D.Double();p.moveTo(6,3);p.lineTo(18,3);p.quadTo(21,3,21,6);p.lineTo(21,15);p.quadTo(21,18,18,18);p.lineTo(10,18);p.lineTo(5,22);p.lineTo(5,18);p.quadTo(3,18,3,15);p.lineTo(3,6);p.quadTo(3,3,6,3);g.draw(p);g.draw(new Line2D.Double(7,8,17,8));g.draw(new Line2D.Double(7,12,14,12));}
                default->{}
            }
        }finally{g.dispose();}
    }
}
