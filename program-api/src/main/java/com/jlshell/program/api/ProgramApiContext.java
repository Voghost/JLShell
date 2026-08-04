package com.jlshell.program.api;

import java.util.concurrent.Executor;

/** Program API SPI 可访问的宿主能力，不暴露主程序的 core 类型。 */
public interface ProgramApiContext {

    ProgramApiRegistry registry();

    ProgramSessionService sessions();

    String apiToken();

    Executor executor();

    /** 宿主账号网关；旧 Host 返回不可用实现。 */
    default AccountSessionService accountSession() {
        return AccountSessionService.unavailable();
    }
}
