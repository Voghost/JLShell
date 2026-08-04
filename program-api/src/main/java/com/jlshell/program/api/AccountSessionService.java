package com.jlshell.program.api;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;

/**
 * Program 插件的宿主账号网关。
 *
 * <p>实现负责把已加密保存的宿主令牌附加到受允许的同源 API 请求；插件只能获取
 * {@link AccountSession} 与 JSON 响应，不能读取、保存或刷新 JWT。</p>
 */
public interface AccountSessionService {

    AccountSession snapshot();

    CompletableFuture<JsonElement> request(AccountRequest request);

    static AccountSessionService unavailable() {
        return UnavailableAccountSessionService.INSTANCE;
    }
}

final class UnavailableAccountSessionService implements AccountSessionService {
    static final UnavailableAccountSessionService INSTANCE = new UnavailableAccountSessionService();

    private UnavailableAccountSessionService() {
    }

    @Override
    public AccountSession snapshot() {
        return AccountSession.signedOut("", "");
    }

    @Override
    public CompletableFuture<JsonElement> request(AccountRequest request) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Host account session is unavailable"));
    }
}
