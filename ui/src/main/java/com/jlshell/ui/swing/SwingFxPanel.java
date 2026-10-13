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
    private final ThemeService themes;
    private final ChangeListener<Object> themeListener = (obs, oldValue, value) -> refreshTheme();
    private boolean closed;
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
        FxThread.run(() -> {
            if (closed) return;
            // 宿主主题只作用于包装节点，避免覆盖插件根节点的 inline style。
            Parent root = new BorderPane(node);
            Scene scene = new Scene(root);
            themes.apply(scene);
            setScene(scene);
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
            themes.currentThemeProperty().removeListener(themeListener);
            themes.accentColorProperty().removeListener(themeListener);
            setScene(null);
        });
    }
}
