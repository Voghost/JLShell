package com.jlshell.plugin.api.project;

import com.jlshell.plugin.api.lifecycle.Registration;

/** Program 插件注册项目创建 UI 扩展点的入口。 */
public interface ProjectIntegration {

    boolean available();

    Registration register(ProjectCreationContribution contribution);

    static ProjectIntegration unavailable() {
        return UnavailableProjectIntegration.INSTANCE;
    }
}

final class UnavailableProjectIntegration implements ProjectIntegration {
    static final UnavailableProjectIntegration INSTANCE = new UnavailableProjectIntegration();

    private UnavailableProjectIntegration() {
    }

    @Override public boolean available() { return false; }

    @Override public Registration register(ProjectCreationContribution contribution) {
        return Registration.noop();
    }
}
