package com.jlshell.plugin.api.project;

import java.util.Optional;

import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import javafx.scene.Node;

/** Program 插件对“新建项目”表单贡献的一块独立 UI。 */
public interface ProjectCreationContribution {

    String id();

    default int order() {
        return 0;
    }

    Node createView(ProjectCreationContext context);

    default Optional<String> validate(ProjectCreationContext context) {
        return Optional.empty();
    }

    default void onProjectCreated(ProjectCreatedEvent event, ProjectCreationContext context) {
    }
}
