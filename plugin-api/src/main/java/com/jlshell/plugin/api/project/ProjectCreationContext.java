package com.jlshell.plugin.api.project;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.beans.property.ReadOnlyStringProperty;

/** 单次“新建项目”交互的上下文；state 仅在该次对话框生命周期内存在。 */
public final class ProjectCreationContext {

    private final ReadOnlyStringProperty name;
    private final ReadOnlyStringProperty description;
    private final Map<String, String> state = new ConcurrentHashMap<>();

    public ProjectCreationContext(ReadOnlyStringProperty name, ReadOnlyStringProperty description) {
        this.name = name;
        this.description = description;
    }

    public ReadOnlyStringProperty nameProperty() {
        return name;
    }

    public ReadOnlyStringProperty descriptionProperty() {
        return description;
    }

    public String name() {
        return name.get();
    }

    public String description() {
        return description.get();
    }

    public void putState(String key, String value) {
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
    }

    public String state(String key) {
        return state.get(key);
    }

    public Map<String, String> stateSnapshot() {
        return Map.copyOf(state);
    }
}
