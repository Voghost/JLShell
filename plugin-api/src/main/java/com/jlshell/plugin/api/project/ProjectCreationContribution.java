package com.jlshell.plugin.api.project;

import java.util.Optional;

import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import com.jlshell.plugin.api.event.ProjectUpdatedEvent;
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

    /**
     * 为已有项目贡献配置、状态和修复入口。返回 {@code null} 表示该插件不参与已有项目管理。
     * 这是 1.2.0 新增的兼容扩展；只实现新建项目能力的旧插件无需修改。
     */
    default Node createManagementView(ProjectManagementContext context) {
        return null;
    }

    default Optional<String> validateManagement(ProjectManagementContext context) {
        return Optional.empty();
    }

    default void onProjectUpdated(ProjectUpdatedEvent event, ProjectManagementContext context) {
    }
}
