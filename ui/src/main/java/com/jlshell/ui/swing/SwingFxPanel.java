package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;

/** 兼容现有 JavaFX 插件及业务表单；不改变插件返回 Node 的 SDK 契约。 */
public final class SwingFxPanel extends JFXPanel implements AutoCloseable {
    /** Marks a third-party subtree inside a host form; its controls must not be translated. */
    static final class PluginView extends BorderPane {
        final ThemeService themes;
        PluginView(Node node, ThemeService themes) { super(node); this.themes = themes; }
    }

    public static Node preservePluginView(Node node, ThemeService themes) {
        return node == null ? null : new PluginView(node, themes);
    }

    private final ThemeService themes;
    private final ChangeListener<Object> themeListener = (obs, oldValue, value) -> refreshTheme();
    private boolean closed;
    private com.jlshell.ui.support.PluginOpenTiming pluginTiming;
    private boolean ownsDialogKeys;
    public void setOwnsDialogKeys(boolean value) { ownsDialogKeys = value; }
    @Override protected boolean processKeyBinding(javax.swing.KeyStroke key, java.awt.event.KeyEvent event,
                                                   int condition, boolean pressed) {
        // JFXPanel 已先把事件送给 FX；阻止父级 Swing 默认按钮再次执行 Enter/Escape。
        if (ownsDialogKeys && (event.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER
                || event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE)) return true;
        return super.processKeyBinding(key, event, condition, pressed);
    }

    public SwingFxPanel(ThemeService themes) {
        this.themes = themes;
        FxThread.run(() -> {
            if (closed) return;
            themes.currentThemeProperty().addListener(themeListener);
            themes.accentColorProperty().addListener(themeListener);
        });
    }

    public void setView(Node node) {
        setView(node, false);
    }

    public void setDialogView(Node node) {
        setView(node, true);
    }

    public void setPluginView(Node node, com.jlshell.ui.support.PluginOpenTiming timing) {
        pluginTiming = timing;
        setView(node, false, timing);
    }

    private void setView(Node node, boolean dialog) { setView(node, dialog, null); }

    private void setView(Node node, boolean dialog, com.jlshell.ui.support.PluginOpenTiming timing) {
        var queue = timing == null ? null : timing.step("fx-bridge-queue");
        FxThread.run(() -> {
            if (queue != null) queue.close();
            if (closed) { if (timing != null) timing.finish("closed-before-display"); return; }
            var span = timing == null ? null : timing.step("fx-scene-create");
            try {
                // 宿主主题只作用于包装节点，避免覆盖插件根节点的 inline style。
                com.jlshell.ui.support.BundledFontLoader.load();
                Parent root = new BorderPane(node);
                if (dialog) root.getStyleClass().add("idea-dialog-root");
                Scene scene = new Scene(root);
                themes.apply(scene);
                setScene(scene);
                if (timing != null) timing.observeFxContent(node);
            } catch (RuntimeException | Error error) {
                if (span != null) { span.failed(error); timing.failed(error); }
                throw error;
            } finally { if (span != null) span.close(); }
        });
    }

    private void refreshTheme() {
        if (!closed && getScene() != null) themes.apply(getScene());
    }

    @Override
    public void close() {
        FxThread.run(() -> {
            if (closed) return;
            closed = true;
            if (pluginTiming != null) { pluginTiming.finish("closed-before-layout"); pluginTiming = null; }
            themes.currentThemeProperty().removeListener(themeListener);
            themes.accentColorProperty().removeListener(themeListener);
            setScene(null);
        });
    }
}
