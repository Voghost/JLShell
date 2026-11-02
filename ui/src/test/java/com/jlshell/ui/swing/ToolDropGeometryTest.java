package com.jlshell.ui.swing;

import java.awt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolDropGeometryTest {
    @Test void usesVisibleDockBoundsRatherThanFixedWindowPercentages() {
        var geometry=new ToolDropGeometry(new Rectangle(0,0,1200,800),new Rectangle(0,688,1200,112),null,null,
                new Rectangle(820,0,380,684),380);
        var target=geometry.resolve(new Point(1000,730),null);
        assertEquals(ToolDockState.Anchor.BOTTOM_RIGHT,target.anchor());
        assertEquals(new Rectangle(600,688,600,112),target.bounds());
        assertEquals(ToolDockState.Anchor.RIGHT,geometry.resolve(new Point(1000,650),null).anchor());
        assertNull(geometry.resolve(new Point(600,350),null));
        assertNull(geometry.resolve(new Point(600,805),null));
    }
    @Test void followsActualDividerAndHasQuarterHeightFallbackOnlyWhenClosed() {
        var pane=new Rectangle(0,0,1200,800);var bottom=new Rectangle(0,500,1200,300);
        var geometry=new ToolDropGeometry(pane,bottom,new Rectangle(0,500,835,300),new Rectangle(839,500,361,300),null,380);
        assertEquals(ToolDockState.Anchor.BOTTOM_LEFT,geometry.resolve(new Point(700,700),null).anchor());
        assertEquals(new Rectangle(839,500,361,300),geometry.resolve(new Point(1000,700),null).bounds());
        var closed=new ToolDropGeometry(pane,null,null,null,null,380);
        assertEquals(new Rectangle(600,600,600,200),closed.resolve(new Point(1210,790),ToolDockState.Anchor.BOTTOM_RIGHT).bounds());
        assertNull(closed.resolve(new Point(1210,790),null));
    }
}
