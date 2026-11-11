package com.jlshell.plugin.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jlshell.plugin.api.ui.ObservableValue;

class ProjectManagementContextTest {

    @Test
    void exposesProjectIdentityAndIsolatesTemporaryState() {
        var name = ObservableValue.constant("Remote");
        var description = ObservableValue.constant("Production servers");
        ProjectManagementContext context = new ProjectManagementContext("project-1", name, description);

        context.putState("link.enabled", "true");

        assertThat(context.projectId()).isEqualTo("project-1");
        assertThat(context.name()).isEqualTo("Remote");
        assertThat(context.description()).isEqualTo("Production servers");
        assertThat(context.state("link.enabled")).isEqualTo("true");
        assertThat(context.stateSnapshot()).containsEntry("link.enabled", "true");

        context.putState("link.enabled", null);
        assertThat(context.state("link.enabled")).isNull();
    }
}
