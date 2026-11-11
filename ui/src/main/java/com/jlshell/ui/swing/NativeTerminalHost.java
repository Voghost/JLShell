package com.jlshell.ui.swing;

import com.jlshell.terminal.service.TerminalViewHandle;

/** UI 承载契约。终端控制器在 EDT 发起回调，窗口负责管理组件生命周期。 */
public interface NativeTerminalHost {
    void attach(TerminalViewHandle handle);
    void disconnected(String reason, Runnable reconnect);
}
