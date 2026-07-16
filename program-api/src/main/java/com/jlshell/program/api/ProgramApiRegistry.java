package com.jlshell.program.api;

import java.util.Map;

/** 供 {@link ProgramApiProvider} 注册 JSON-RPC 方法的注册表。 */
public interface ProgramApiRegistry {

    void register(String method, ProgramApiMethod handler);

    Map<String, ProgramApiMethod> methods();
}
