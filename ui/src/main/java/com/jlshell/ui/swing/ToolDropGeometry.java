package com.jlshell.ui.swing;

import java.awt.Point;
import java.awt.Rectangle;

/** IDEA ToolWindowDropArea: prefer actual dock bounds and splitter children, then use a quarter-height fallback. */
final class ToolDropGeometry {
    record Target(ToolDockState.Anchor anchor, Rectangle bounds) {}
    private final Rectangle pane, bottom, first, second, right;
    ToolDropGeometry(Rectangle pane, Rectangle bottom, Rectangle first, Rectangle second, Rectangle right, int sideWidth) {
        this.pane=new Rectangle(pane);
        if (bottom != null) this.bottom=new Rectangle(bottom);
        else {
            int height=Math.min(pane.height,Math.max(200,pane.height/4));
            this.bottom=new Rectangle(pane.x,pane.y+pane.height-height,pane.width,height);
        }
        if(first!=null && second!=null) {this.first=new Rectangle(first);this.second=new Rectangle(second);}
        else {
            int half=this.bottom.width/2;
            this.first=new Rectangle(this.bottom.x,this.bottom.y,half,this.bottom.height);
            this.second=new Rectangle(this.bottom.x+half,this.bottom.y,this.bottom.width-half,this.bottom.height);
        }
        int width=Math.min(pane.width,Math.max(180,sideWidth));
        this.right=right==null ? new Rectangle(pane.x+pane.width-width,pane.y,width,Math.max(0,this.bottom.y-pane.y)) : new Rectangle(right);
    }
    Target resolve(Point point,ToolDockState.Anchor stripe) {
        if(stripe!=null) return target(stripe);
        if(!pane.contains(point))return null;
        if(bottom.contains(point))return target(point.x>=second.x?ToolDockState.Anchor.BOTTOM_RIGHT:ToolDockState.Anchor.BOTTOM_LEFT);
        return right.contains(point)?target(ToolDockState.Anchor.RIGHT):null;
    }
    private Target target(ToolDockState.Anchor anchor) {
        return new Target(anchor,new Rectangle(switch(anchor){case RIGHT->right;case BOTTOM_LEFT->first;case BOTTOM_RIGHT->second;}));
    }
}
