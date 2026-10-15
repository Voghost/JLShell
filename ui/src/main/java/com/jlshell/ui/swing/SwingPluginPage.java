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

    private SwingPluginPage(JComponent content) {
        super(new BorderLayout()); this.content = content; setName("plugin.nativePage"); add(content);
    }

    static Prepared prepare(Tab tab, I18nService i18n, ThemeService themes) {
        String title = tab.getText(); boolean closable = tab.isClosable();
        Runnable closeTab = () -> com.jlshell.ui.support.FxThread.run(() -> {
            var owner = tab.getTabPane(); if (owner == null) return;
            owner.getTabs().remove(tab);
            if (tab.getOnClosed() != null) tab.getOnClosed().handle(new javafx.event.Event(Tab.CLOSED_EVENT));
        });
        java.util.function.Function<JComponent,SwingPluginPage> wrap = body -> {
            var page = new SwingPluginPage(body);
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
            return new Prepared(() -> wrap.apply((JComponent) supplier.get()), () -> {});
        }
        var renderer = new SwingFormRenderer(i18n);
        try {
            var factory = renderer.prepare(tab.getContent());
            return new Prepared(() -> wrap.apply(factory.get()), renderer::close);
        } catch (IllegalArgumentException unsupported) {
            renderer.close();
            // 未升级的第三方自绘 Node 仍可打开；不影响原生插件页和列表。
            var node = tab.getContent();
            return new Prepared(() -> {
                var bridge = new SwingFxPanel(themes); bridge.setView(node);
                return wrap.apply(bridge);
            }, () -> {});
        }
    }

    void updateTitle(String title) { if (titleLabel != null) titleLabel.setText(title); }

    @Override public void close() {
        if (closed) return; closed = true;
        if (content instanceof AutoCloseable resource) {
            try { resource.close(); } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(SwingPluginPage.class).warn("Cannot close plugin view", error);
            }
        }
        removeAll();
    }
}
