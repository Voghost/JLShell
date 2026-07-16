package com.jlshell.ui.service.account;

import com.jlshell.core.model.SessionState;
import com.jlshell.core.service.SessionManager;
import java.util.Objects;

/** 按真实 SSH 会话状态计算账号统计中的当前连接数。 */
public final class AccountConnectionCounter {
    private AccountConnectionCounter() {
    }

    public static int connectedSessions(SessionManager sessionManager) {
        long count = Objects.requireNonNull(sessionManager).listSessions().stream()
                .filter(session -> session.state() == SessionState.CONNECTED)
                .count();
        return Math.toIntExact(count);
    }
}
