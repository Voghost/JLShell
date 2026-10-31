package com.jlshell.ui.swing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolDockStateTest {
    @Test void restoresIndependentToolsAndSizesWithArbitraryPluginIds() {
        var layout = new ToolDockState();
        layout.positions.put("files", ToolDockState.Anchor.BOTTOM_RIGHT);
        layout.positions.put("plugin:com.example/工具|a:b", ToolDockState.Anchor.BOTTOM_LEFT);
        layout.leftVisible = false; layout.bottomRightVisible = true; layout.rightVisible = true;
        layout.bottomRightSelected = "files"; layout.rightSelected = "plugin:com.example/工具|a:b";
        layout.sidebar = 310; layout.height = 340; layout.width = 450;
        var restored = ToolDockState.decode(layout.encode());
        assertEquals(layout.positions, restored.positions);
        assertFalse(restored.leftVisible); assertTrue(restored.bottomRightVisible); assertTrue(restored.rightVisible);
        assertEquals(layout.bottomRightSelected,restored.bottomRightSelected); assertEquals(layout.rightSelected,restored.rightSelected);
        assertEquals(310, restored.sidebar); assertEquals(340, restored.height); assertEquals(450, restored.width);
        assertEquals(ToolDockState.Anchor.RIGHT, restored.anchor("plugins"));
    }
    @Test void malformedOrFutureLayoutFallsBackToDefaults() {
        for (String value : new String[]{"", "2|invalid", "1|x|200|300", "1|280|260|380|invalid"}) {
            var state = ToolDockState.decode(value);
            assertTrue(state.positions.isEmpty()); assertEquals(280, state.sidebar);
            assertEquals(ToolDockState.Anchor.BOTTOM_LEFT, state.anchor("monitor"));
            assertEquals(ToolDockState.Anchor.RIGHT, state.anchor("files"));
        }
    }
}
