package com.jlshell.terminal.support;

import com.jediterm.terminal.model.*;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Opt-in local paint benchmark; never asserts timing against a shared CI machine. */
@EnabledIfSystemProperty(named="jlshell.terminal.benchmark",matches="true")
class TerminalScrollPerformanceTest {
    /** Real AWT window/repaint scheduling, with synthetic macOS pixel events and concurrent output. */
    @Test void measureVisibleMacTrackpadSequence() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        javax.swing.SwingUtilities.invokeLater(() -> {
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 14, false, 1), TerminalColorScheme.dark());
            var style = new StyleState(); style.setDefaultStyle(settings.defaultTextStyle());
            var buffer = new TerminalTextBuffer(160, 40, style);
            var panel = new LiveProbe(settings, buffer, style);
            var bar = new javax.swing.JScrollBar(); panel.init(bar);
            var terminal = new JediTerminal(panel, buffer, style); panel.setCoordAccessor(terminal);
            for (int i = 0; i < 500; i++) output(terminal, i);
            var frame = new javax.swing.JFrame("Terminal scroll scheduling probe");
            frame.add(panel); frame.pack(); frame.setVisible(true);
            var timer = new javax.swing.Timer(8, null);
            int[] count = {0};
            timer.addActionListener(event -> {
                try {
                    if (count[0] == 0) panel.getVerticalScrollModel().setValue(-100);
                    // Allow class loading/JIT and first-window work to settle before sampling.
                    if (count[0] == 60) panel.recording = true;
                    if (panel.pendingInput == 0) panel.pendingInput = System.nanoTime();
                    var wheel = new MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                            10, 10, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1, -.13);
                    panel.handleMouseWheelEvent(wheel, bar);
                    org.junit.jupiter.api.Assertions.assertTrue(wheel.isConsumed());
                    if (count[0] % 8 == 0) output(terminal, 500 + count[0]);
                    if (++count[0] == 240) {
                        timer.stop();
                        panel.recording = false;
                        System.out.printf(java.util.Locale.ROOT,
                                "VISIBLE_TRACKPAD events=180 paints=%d paintP50Ms=%.3f paintP95Ms=%.3f "
                                        + "paintGapP50Ms=%.3f paintGapP95Ms=%.3f inputToPaintStartP95Ms=%.3f size=%dx%d%n",
                                panel.costs.size(), percentile(panel.costs, .5), percentile(panel.costs, .95),
                                percentile(panel.gaps, .5), percentile(panel.gaps, .95), percentile(panel.delays, .95),
                                panel.getWidth(), panel.getHeight());
                        org.junit.jupiter.api.Assertions.assertFalse(panel.costs.isEmpty());
                        panel.dispose(); frame.dispose(); completed.complete(null);
                    }
                } catch (Throwable failure) {
                    timer.stop(); panel.dispose(); frame.dispose(); completed.completeExceptionally(failure);
                }
            });
            timer.start();
        });
        completed.get(45, TimeUnit.SECONDS);
    }

    private static void output(JediTerminal terminal, int i) {
        terminal.writeCharacters("2026-10-31 09:00:00 INFO [worker-1] com.example.Service - record=" + i + " result=SUCCESS");
        terminal.carriageReturn(); terminal.newLine();
    }

    private static double percentile(java.util.List<Long> samples, double percentile) {
        if (samples.isEmpty()) return 0;
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[(int)Math.ceil(sorted.length * percentile) - 1] / 1e6;
    }

    private static final class LiveProbe extends RefreshableTerminalPanel {
        final java.util.List<Long> costs = new java.util.ArrayList<>(), gaps = new java.util.ArrayList<>(), delays = new java.util.ArrayList<>();
        boolean recording;
        long lastPaint, pendingInput;
        LiveProbe(JlshellSettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
            super(settings, buffer, style, settings, key -> key, new TerminalWheelInput(true));
        }
        @Override public void paintComponent(java.awt.Graphics graphics) {
            long start = System.nanoTime();
            super.paintComponent(graphics);
            if (!recording) return;
            costs.add(System.nanoTime() - start);
            if (lastPaint != 0) gaps.add(start - lastPaint);
            lastPaint = start;
            if (pendingInput != 0) { delays.add(start - pendingInput); pendingInput = 0; }
        }
    }

    @Test void measureLogScrollPaintCost() throws Exception {
        SwingExecutors.runOnEdtAsync(()->{
            var settings=new JlshellSettingsProvider(new FontProfile("Monospaced",14,false,1),TerminalColorScheme.dark());
            var state=new StyleState();state.setDefaultStyle(settings.defaultTextStyle());
            var buffer=new TerminalTextBuffer(160,40,state);
            var panel=new RefreshableTerminalPanel(settings,buffer,state,settings,key->key);
            var bar=new javax.swing.JScrollBar();panel.init(bar);panel.setSize(panel.getPreferredSize());
            var terminal=new JediTerminal(panel,buffer,state);panel.setCoordAccessor(terminal);
            String line="2026-10-31 09:00:00 INFO [worker-pool-1] com.example.application.Service - requestId=0123456789abcdef result=SUCCESS processing remote log record ";
            for(int i=0;i<500;i++){terminal.writeCharacters(line);terminal.carriageReturn();terminal.newLine();}
            var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
            try {
                var g=image.createGraphics();try{panel.paintComponent(g);}finally{g.dispose();}
                long[] samples=new long[100];
                for(int i=-20;i<samples.length;i++){
                    panel.handleMouseWheelEvent(new MouseWheelEvent(panel,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,
                            10,10,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,-1,-.07),bar);
                    long start=System.nanoTime();g=image.createGraphics();try{panel.paintComponent(g);}finally{g.dispose();}
                    if(i>=0)samples[i]=System.nanoTime()-start;
                }
                Arrays.sort(samples);
                System.out.printf(java.util.Locale.ROOT,"SCROLL_PAINT frames=100 p50Ms=%.3f p95Ms=%.3f maxMs=%.3f size=%dx%d%n",
                        samples[50]/1e6,samples[95]/1e6,samples[99]/1e6,panel.getWidth(),panel.getHeight());
            }finally{panel.dispose();}
        }).get(30,TimeUnit.SECONDS);
    }
}
