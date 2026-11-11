package com.jlshell.ui.model;

import com.jlshell.core.support.MutableValue;
import com.jlshell.plugin.api.ui.ObservableValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.JComponent;

/** 标签的稳定标识、标题和延迟创建的原生页面。由 Swing EDT 管理。 */
public class WorkspacePage {
    private final MutableValue<String> title;
    private final MutableValue<Supplier<JComponent>> content = new MutableValue<>(null);
    private final Map<String, Object> properties = new HashMap<>();
    private boolean closable = true;
    private boolean closed;
    private Runnable onClosed = () -> { };

    public WorkspacePage(String title) {
        this.title = new MutableValue<>(title);
    }

    public String getText() {
        return title.get();
    }

    public void setText(String text) {
        title.set(text);
    }

    public ObservableValue<String> textProperty() {
        return title;
    }

    public Supplier<JComponent> getContent() {
        return content.get();
    }

    public void setContent(Supplier<JComponent> factory) {
        content.set(factory);
    }

    public ObservableValue<Supplier<JComponent>> contentProperty() {
        return content;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public boolean isClosable() {
        return closable;
    }

    public void setClosable(boolean value) {
        closable = value;
    }

    public void setOnClosed(Runnable action) {
        onClosed = Objects.requireNonNull(action);
    }

    public void closed() {
        if (closed) return;
        closed = true;
        onClosed.run();
        onClosed = () -> { };
    }
}
