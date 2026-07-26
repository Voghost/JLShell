package com.jlshell.plugin.api.lifecycle;

/** 可撤销的插件注册句柄。close 必须幂等。 */
@FunctionalInterface
public interface Registration extends AutoCloseable {

    @Override
    void close();

    static Registration noop() {
        return () -> { };
    }
}
