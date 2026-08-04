package com.jlshell.plugin.api.project;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javafx.beans.property.ReadOnlyStringProperty;

/** 已有项目在一次“管理项目”交互中的只读身份和插件临时表单状态。 */
public final class ProjectManagementContext {

    private final String projectId;
    private final ReadOnlyStringProperty name;
    private final ReadOnlyStringProperty description;
    private final Map<String, String> state = new ConcurrentHashMap<>();

    public ProjectManagementContext(String projectId, ReadOnlyStringProperty name,
                                    ReadOnlyStringProperty description) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
    }

    public String projectId() {
        return projectId;
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
        Objects.requireNonNull(key, "key");
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
