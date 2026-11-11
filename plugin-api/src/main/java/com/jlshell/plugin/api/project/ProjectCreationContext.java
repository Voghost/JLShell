package com.jlshell.plugin.api.project;

import com.jlshell.plugin.api.ui.ObservableValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



/** 单次“新建项目”交互的上下文；state 仅在该次对话框生命周期内存在。 */
public final class ProjectCreationContext {

    private final ObservableValue<String> name;
    private final ObservableValue<String> description;
    private final Map<String, String> state = new ConcurrentHashMap<>();

    public ProjectCreationContext(ObservableValue<String> name, ObservableValue<String> description) {
        this.name = name;
        this.description = description;
    }

    public ObservableValue<String> nameProperty() {
        return name;
    }

    public ObservableValue<String> descriptionProperty() {
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
