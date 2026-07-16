package com.jlshell.program.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的程序 API 方法注册表实现。 */
public final class DefaultProgramApiRegistry implements ProgramApiRegistry {
    private final Map<String, ProgramApiMethod> methods = new ConcurrentHashMap<>();

    @Override
    public void register(String method, ProgramApiMethod handler) {
        String name = Objects.requireNonNull(method, "method").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (methods.putIfAbsent(name, Objects.requireNonNull(handler, "handler")) != null) {
            throw new IllegalStateException("program API method already registered: " + name);
        }
    }

    @Override
    public Map<String, ProgramApiMethod> methods() {
        return Map.copyOf(methods);
    }
}
