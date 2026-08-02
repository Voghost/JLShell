package com.jlshell.plugin.api.session;

import com.jlshell.plugin.api.lifecycle.Registration;

/** Program 插件向 SSH 会话工作区贡献功能入口的注册接口。 */
public interface ProgramSessionIntegration {

    boolean available();

    /**
     * 注册此 Program 插件唯一的会话贡献。
     *
     * <p>注册句柄关闭时，宿主会同时关闭该贡献在所有会话中的活动实例。</p>
     */
    Registration register(ProgramSessionContribution contribution);

    static ProgramSessionIntegration unavailable() {
        return UnavailableProgramSessionIntegration.INSTANCE;
    }
}

final class UnavailableProgramSessionIntegration implements ProgramSessionIntegration {
    static final UnavailableProgramSessionIntegration INSTANCE = new UnavailableProgramSessionIntegration();

    private UnavailableProgramSessionIntegration() {
    }

    @Override public boolean available() { return false; }

    @Override
    public Registration register(ProgramSessionContribution contribution) {
        return Registration.noop();
    }
}
