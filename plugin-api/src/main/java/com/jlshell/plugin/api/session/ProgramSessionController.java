package com.jlshell.plugin.api.session;

/** Program 插件单个会话贡献的生命周期控制器。 */
@FunctionalInterface
public interface ProgramSessionController extends AutoCloseable {

    @Override
    void close();

    static ProgramSessionController noop() {
        return () -> { };
    }
}
