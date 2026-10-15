package com.jlshell.ui.swing;

import com.jlshell.terminal.service.TerminalViewHandle;

/** UI 承载契约。回调来自 FX 线程；实现负责异步切换到 EDT。 */
public interface NativeTerminalHost {
    void attach(TerminalViewHandle handle);
    void disconnected(String reason, Runnable reconnect);
}
