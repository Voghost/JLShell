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
    @Test void sqlitePersistsWindowSizeAndIndependentToolOrder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        try (var source = com.jlshell.data.config.DatabaseFactory.createDataSource("jdbc:sqlite:" + dir.resolve("settings.db"))) {
            var jdbi = com.jlshell.data.config.DatabaseFactory.createJdbi(source);
            com.jlshell.data.config.DatabaseFactory.initSchema(jdbi);
            var settings = new com.jlshell.data.service.JdbiAppSettingsService(jdbi);
            var layout = new ToolDockState();
            layout.sidebar = 330; layout.width = 440; layout.height = 310;
            layout.positions.put("plugin:lazy", ToolDockState.Anchor.RIGHT);
            layout.positions.put("plugins", ToolDockState.Anchor.RIGHT);
            layout.positions.put("files", ToolDockState.Anchor.BOTTOM_RIGHT);
            layout.positions.put("monitor", ToolDockState.Anchor.BOTTOM_LEFT);
            settings.set(ToolDockState.SETTING, layout.encode());
            settings.set("ui.workspace.windowSize", "1200x750");
            var restored = ToolDockState.decode(new com.jlshell.data.service.JdbiAppSettingsService(jdbi).get(ToolDockState.SETTING).orElseThrow());
            assertEquals(java.util.List.copyOf(layout.positions.keySet()), java.util.List.copyOf(restored.positions.keySet()));
            assertEquals(layout.positions, restored.positions);
            assertEquals(330, restored.sidebar); assertEquals(440, restored.width); assertEquals(310, restored.height);
            assertEquals("1200x750", settings.get("ui.workspace.windowSize").orElseThrow());
        }
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
