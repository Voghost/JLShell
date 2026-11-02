package com.jlshell.terminal.support;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.model.TerminalSelection;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.service.TerminalHighlightService;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import com.jediterm.core.compatibility.Point;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TerminalHighlightRenderingTest {
    @Test void paintsWithoutChangingBufferAndRefreshesExistingTerminal() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var values = new HashMap<String,String>();
            AppSettingsService storage = new AppSettingsService() {
                public Optional<String> get(String key) { return Optional.ofNullable(values.get(key)); }
                public void set(String key, String value) { values.put(key,value); }
                public void remove(String key) { values.remove(key); }
            };
            var service = new TerminalHighlightService(storage);
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 16, false, 1), TerminalColorScheme.dark());
            settings.setHighlights(service);
            var widget = JlshellJediTermWidget.create(80, 12, settings, key -> key);
            try {
                widget.setSize(800, 240); layout(widget);
                widget.getTerminal().writeCharacters("INFO normal WARN slow ERROR failed");
                widget.getTerminal().carriageReturn();
                widget.getTerminal().newLine();
                widget.getTerminal().characterAttributes(new TextStyle(TerminalColor.rgb(0,255,0), null));
                widget.getTerminal().writeCharacters("ERROR ANSI green");
                String raw = widget.getTerminalTextBuffer().getScreenLines();
                TextStyle rawStyle = widget.getTerminalTextBuffer().getStyleAt(0,0);
                BufferedImage painted = paint(widget);
                try { javax.imageio.ImageIO.write(painted,"png",java.nio.file.Path.of("target/highlight-rendering.png").toFile()); }
                catch (java.io.IOException error) { throw new AssertionError(error); }
                assertTrue(count(painted, Color.decode("#F87171")) > 0, "ERROR must render red");
                assertTrue(count(painted, Color.decode("#38BDF8")) > 0, "INFO must render cyan");
                assertTrue(count(painted, Color.GREEN) > 0, "explicit ANSI foreground is retained");
                assertEquals(raw, widget.getTerminalTextBuffer().getScreenLines());
                assertEquals(rawStyle, widget.getTerminalTextBuffer().getStyleAt(0,0));
                var finder = new com.jediterm.terminal.SubstringFinder("ERROR", false);
                int entryX = 0;
                for (var entry : widget.getTerminalTextBuffer().getLine(0).getEntries()) {
                    for (int i=0;i<entry.getLength();i++) finder.nextChar(entryX, 0, entry.getText(), i);
                    entryX += entry.getLength();
                }
                widget.getTerminalPanel().setFindResult(finder.getResult());
                assertEquals(0, count(paint(widget), Color.decode("#F87171")), "Search matches take priority");
                widget.getTerminalPanel().setFindResult(null);
                // Selection uses JediTerm's normal draw pass and must override log coloring.
                var selection = com.jediterm.terminal.ui.TerminalPanel.class.getDeclaredField("mySelection");
                selection.setAccessible(true); selection.set(widget.getTerminalPanel(), new TerminalSelection(new Point(0,0), new Point(80,0)));
                assertEquals(0, count(paint(widget), Color.decode("#F87171")));
                selection.set(widget.getTerminalPanel(), null);
                service.update(new TerminalHighlightConfig(false, service.config().rules()));
                assertEquals(0, count(paint(widget), Color.decode("#F87171")));
                assertFalse(new TerminalHighlightService(storage).config().enabled());
                service.update(TerminalHighlightConfig.defaults());
                assertTrue(count(paint(widget), Color.decode("#F87171")) > 0);
                widget.getTerminal().useAlternateBuffer(true);
                widget.getTerminal().characterAttributes(settings.defaultTextStyle());
                widget.getTerminal().writeCharacters("ERROR full screen");
                assertEquals(0, count(paint(widget), Color.decode("#F87171")));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            finally { widget.close(); }
        }).get(15, TimeUnit.SECONDS);
    }
    private static void layout(java.awt.Container container) {
        container.doLayout(); for (var child : container.getComponents()) if (child instanceof java.awt.Container c) layout(c);
    }
    private static BufferedImage paint(JlshellJediTermWidget widget) {
        var image = new BufferedImage(800, 240, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { widget.paint(g); } finally { g.dispose(); }
        return image;
    }
    private static int count(BufferedImage image, Color color) {
        int total = 0;
        for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++)
            if (image.getRGB(x,y) == color.getRGB()) total++;
        return total;
    }
}
