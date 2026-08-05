package com.jlshell.plugin.api.connection;

import com.jlshell.plugin.api.lifecycle.Registration;

/** Program 插件注册连接前本地回环路由的宿主接口。 */
public interface ProgramConnectionIntegration {

    boolean available();

    Registration register(ProgramConnectionRouteContribution contribution);

    static ProgramConnectionIntegration unavailable() {
        return UnavailableProgramConnectionIntegration.INSTANCE;
    }
}

final class UnavailableProgramConnectionIntegration implements ProgramConnectionIntegration {
    static final UnavailableProgramConnectionIntegration INSTANCE = new UnavailableProgramConnectionIntegration();

    private UnavailableProgramConnectionIntegration() {
    }

    @Override public boolean available() { return false; }

    @Override public Registration register(ProgramConnectionRouteContribution contribution) {
        return Registration.noop();
    }
}
