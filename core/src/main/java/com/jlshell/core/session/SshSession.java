package com.jlshell.core.session;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.CommandResult;
import com.jlshell.core.model.ConnectionTarget;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.model.ShellRequest;

/**
 * SSH 会话统一抽象。
 * 后续的 terminal、sftp、plugin 模块只应依赖该接口，而不是具体 SSHJ 实现。
 */
public interface SshSession extends AutoCloseable {

    SessionId sessionId();

    String displayName();

    ConnectionTarget target();

    SessionState state();

    Instant connectedAt();

    /**
     * 执行一次性命令。
     * 更适合探测、后台任务和非持续交互场景。
     */
    CompletableFuture<CommandResult> execute(CommandRequest request);

    /**
     * 打开持续交互式 Shell。
     * 后续 terminal 模块会把返回的输入输出流桥接给 JediTerm。
     */
    CompletableFuture<ShellChannel> openShell(ShellRequest request);

    CompletableFuture<Void> disconnect();

    /**
     * 订阅底层传输断开事件。默认实现为空，具体 SSH 实现可在网络异常、
     * keepalive 失败、远端重置连接时主动通知 UI 和插件层尽快失效。
     */
    default void addDisconnectListener(Consumer<String> listener) {
    }

    default void removeDisconnectListener(Consumer<String> listener) {
    }

    default SessionDescriptor descriptor() {
        return new SessionDescriptor(sessionId(), displayName(), target(), state(), connectedAt());
    }

    /**
     * 为后续扩展模块保留实现细节访问入口。
     * 调用方必须先检查返回值，禁止假设底层一定存在某种实现。
     */
    default <T> Optional<T> unwrap(Class<T> type) {
        return Optional.empty();
    }

    @Override
    default void close() {
        disconnect().join();
    }
}
