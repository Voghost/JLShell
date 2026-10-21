package com.jlshell.ui.support;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.util.Objects;
import java.util.function.Supplier;

/** 仅在用户访问分类时创建页面；整个设置会话复用同一模型，保留未应用的编辑值。 */
public final class DeferredSettingsPage extends StackPane {
    private Supplier<Node> factory;
    private Node content;

    public DeferredSettingsPage(Supplier<Node> factory, String searchText) {
        this.factory = Objects.requireNonNull(factory);
        getProperties().put("jlshell.settingsSearchText", searchText);
    }

    public Node materialize() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Settings pages require FX thread");
        if (content == null) {
            Node created = Objects.requireNonNull(factory.get());
            getChildren().setAll(created);
            content = created;
            factory = null;
        }
        return content;
    }

    public static Node materialize(Node node) {
        return node instanceof DeferredSettingsPage page ? page.materialize() : node;
    }
}
