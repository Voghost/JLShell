package com.jlshell.program.api;

import java.util.concurrent.Executor;

/** Program API SPI 可访问的宿主能力，不暴露主程序的 core 类型。 */
public interface ProgramApiContext {

    ProgramApiRegistry registry();

    ProgramSessionService sessions();

    String apiToken();

    Executor executor();
}
