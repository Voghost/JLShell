package com.jlshell.ui.support;

import com.jlshell.core.support.PerformanceTrace;
import javafx.scene.Node;
import javafx.scene.Scene;

/** Per-click elapsed times, including FX/EDT queueing. Never contains connection or credential data. */
public final class PluginOpenTiming {
    public static final String PROPERTY = "jlshell.plugin.openTiming";
    private final PerformanceTrace trace;
    private final PerformanceTrace.Span fxQueue;

    public PluginOpenTiming(String pluginId) {
        trace = PerformanceTrace.start("plugin-open", pluginId);
        fxQueue = trace.step("fx-queue");
    }

    public PerformanceTrace trace() { return trace; }
    public PerformanceTrace.Span step(String stage) { return trace.step(stage); }
    public void finish(String outcome) { trace.finish(outcome); }
    public void failed(Throwable error) { trace.failed(error); }

    public void mark(String stage) {
        if (stage.equals("fx-dispatch")) fxQueue.close();
        trace.mark(stage);
    }

    /** FX layout is a separate milestone from painting the surrounding Swing container. */
    public void observeFxContent(Node content) {
        Scene scene = content.getScene();
        if (scene != null) observeFxScene(scene, content);
        else content.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends Scene> value,
                                          Scene oldScene, Scene newScene) {
                if (newScene != null) {
                    content.sceneProperty().removeListener(this);
                    observeFxScene(newScene, content);
                }
            }
        });
    }

    private void observeFxScene(Scene scene, Node content) {
        mark("fx-scene-attached");
        scene.addPostLayoutPulseListener(new Runnable() {
            @Override public void run() {
                if (content.getScene() != scene) {
                    scene.removePostLayoutPulseListener(this);
                    finish("detached-before-layout");
                } else if (scene.getWindow() != null && scene.getWindow().isShowing()
                        && treeVisible(content) && content.getLayoutBounds().getWidth() > 0
                        && content.getLayoutBounds().getHeight() > 0) {
                    scene.removePostLayoutPulseListener(this);
                    mark("fx-first-layout");
                    finish("fx-content-laid-out");
                }
            }
        });
        javafx.application.Platform.requestNextPulse();
    }

    private static boolean treeVisible(Node content) {
        for (Node node = content; node != null; node = node.getParent()) {
            if (!node.isVisible()) return false;
        }
        return true;
    }
}
