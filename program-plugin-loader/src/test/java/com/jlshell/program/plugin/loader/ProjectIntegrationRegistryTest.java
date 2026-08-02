package com.jlshell.program.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import com.jlshell.plugin.api.project.ProjectCreationContext;
import com.jlshell.plugin.api.project.ProjectCreationContribution;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

class ProjectIntegrationRegistryTest {

    @Test
    void ordersContributionsAndCleansOnlyOwningPlugin() {
        ProjectIntegrationRegistry registry = new ProjectIntegrationRegistry();
        registry.scoped("plugin-b").register(contribution("later", 20));
        registry.scoped("plugin-a").register(contribution("first", 10));
        registry.scoped("plugin-a").register(contribution("same-order", 20));

        assertThat(registry.contributions())
                .extracting(item -> item.pluginId() + "/" + item.contribution().id())
                .containsExactly("plugin-a/first", "plugin-a/same-order", "plugin-b/later");

        registry.clearForPlugin("plugin-a");
        assertThat(registry.contributions())
                .extracting(ProjectIntegrationRegistry.RegisteredContribution::pluginId)
                .containsExactly("plugin-b");
    }

    @Test
    void registrationHandleIsIdempotentAndDoesNotRemoveReplacement() {
        ProjectIntegrationRegistry registry = new ProjectIntegrationRegistry();
        var first = registry.scoped("plugin-a").register(contribution("setup", 0));
        registry.scoped("plugin-a").register(contribution("setup", 1));

        first.close();
        first.close();

        assertThat(registry.contributions()).hasSize(1);
        assertThat(registry.contributions().getFirst().contribution().order()).isEqualTo(1);
    }

    private static ProjectCreationContribution contribution(String id, int order) {
        return new ProjectCreationContribution() {
            @Override public String id() { return id; }
            @Override public int order() { return order; }
            @Override public Node createView(ProjectCreationContext context) { return null; }
        };
    }
}
