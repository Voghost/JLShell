package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.view.PluginsTabView;
import javafx.scene.control.Tab;
import javax.swing.*;
import java.awt.BorderLayout;
import java.util.function.Supplier;

/** 页面生命周期：Swing 工厂在 EDT 执行，FX 模型监听在 FX 线程释放。 */
final class SwingPluginPage extends JPanel implements AutoCloseable {
    record Prepared(Supplier<SwingPluginPage> factory, Runnable releaseModel) {}
    private final JComponent content;
    private boolean closed;
    private JLabel titleLabel;
    private com.jlshell.ui.support.PluginOpenTiming timing;

    private SwingPluginPage(JComponent content) {
        super(new BorderLayout()); this.content = content; setName("plugin.nativePage"); add(content);
    }

    static Prepared prepare(Tab tab, I18nService i18n, ThemeService themes) {
        String title = tab.getText(); boolean closable = tab.isClosable();
        var timing = (com.jlshell.ui.support.PluginOpenTiming)
                tab.getProperties().get(com.jlshell.ui.support.PluginOpenTiming.PROPERTY);
        var edtQueue = timing == null ? null : timing.step("edt-page-queue");
        Runnable closeTab = () -> com.jlshell.ui.support.FxThread.run(() -> {
            var owner = tab.getTabPane(); if (owner == null) return;
            owner.getTabs().remove(tab);
            if (tab.getOnClosed() != null) tab.getOnClosed().handle(new javafx.event.Event(Tab.CLOSED_EVENT));
        });
        java.util.function.Function<JComponent,SwingPluginPage> wrap = body -> {
            var page = new SwingPluginPage(body);
            page.timing = timing;
            if (closable) {
                JPanel header = new JPanel(new BorderLayout(8,0));
                header.setBorder(BorderFactory.createEmptyBorder(8,12,8,8));
                JLabel label = new JLabel(title); page.titleLabel = label; label.putClientProperty("html.disable", true); header.add(label);
                JButton close = new JButton(new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE));
                close.setName("plugin.page.close"); close.setToolTipText(i18n.get("tab.close")); close.putClientProperty("JButton.buttonType", "toolBarButton");
                close.addActionListener(event -> closeTab.run()); header.add(close,BorderLayout.EAST); page.add(header,BorderLayout.NORTH);
            }
            return page;
        };
        if (tab.getContent() == null) return new Prepared(() -> wrap.apply(new JPanel()), () -> {});
        if (tab.getContent() instanceof PluginsTabView plugins) {
            var factory = plugins.prepareNativeView();
            return new Prepared(() -> wrap.apply(factory.get()), () -> {});
        }
        if (tab.getProperties().get(PluginsTabView.NATIVE_VIEW) instanceof Supplier<?> supplier) {
            return new Prepared(() -> {
                if (edtQueue != null) edtQueue.close();
                if (timing != null) timing.mark("edt-factory-start");
                if (timing == null) return wrap.apply((JComponent) supplier.get());
                try {
                    return timing.trace().call("swing-view-factory", () -> wrap.apply((JComponent) supplier.get()));
                } catch (RuntimeException | Error error) { timing.failed(error); throw error; }
            }, () -> {});
        }
        // openTab(Node) 是完整 JavaFX 页面契约。表单映射器只覆盖宿主已知的控件，
        // 会丢失第三方 Region（如 RichTextFX）、ListCell 图形和图标字体。
        // 保留插件原始节点树；插件通过 openSwingTab 显式选择原生 Swing。
        var node = tab.getContent();
        return new Prepared(() -> {
            if (edtQueue != null) edtQueue.close();
            try {
                var bridge = timing == null ? new SwingFxPanel(themes)
                        : timing.trace().call("javafx-bridge-create", () -> new SwingFxPanel(themes));
                bridge.setPluginView(node, timing);
                return wrap.apply(bridge);
            } catch (RuntimeException | Error error) {
                if (timing != null) timing.failed(error);
                throw error;
            }
        }, () -> {});
    }

    void updateTitle(String title) { if (titleLabel != null) titleLabel.setText(title); }

    @Override protected void paintChildren(java.awt.Graphics graphics) {
        super.paintChildren(graphics);
        if (timing != null && isShowing() && getWidth() > 0 && getHeight() > 0) {
            timing.mark("swing-first-paint");
            if (!(content instanceof SwingFxPanel)) timing.finish("swing-first-paint");
            timing = null;
        }
    }

    @Override public void close() {
        if (closed) return; closed = true;
        if (timing != null) { timing.finish("closed-before-display"); timing = null; }
        if (content instanceof AutoCloseable resource) {
            try { resource.close(); } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(SwingPluginPage.class).warn("Cannot close plugin view", error);
            }
        }
        removeAll();
    }
}
