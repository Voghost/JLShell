package com.jlshell.app.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.jlshell.program.api.AccountRequest;
import com.jlshell.program.api.AccountSession;
import com.jlshell.program.api.AccountSessionService;
import com.jlshell.ui.service.account.AccountService;

/** 把桌面端加密保存的账号会话安全地适配给 Program 插件和 Program API。 */
public final class HostAccountSessionService implements AccountSessionService {

    private final AccountService accounts;

    public HostAccountSessionService(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountSession snapshot() {
        Optional<AccountService.AccountSession> current = accounts.currentSession();
        if (current.isEmpty()) {
            return AccountSession.signedOut(accounts.baseUrl(), accounts.deviceId());
        }
        AccountService.AccountSession session = current.get();
        return new AccountSession(true, accounts.baseUrl(), accounts.deviceId(), session.id(),
                session.username(), session.email(), session.role(), session.expiresAt());
    }

    @Override
    public CompletableFuture<JsonElement> request(AccountRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Account request is required"));
        }
        String body = request.body() == null || request.body().isJsonNull() ? null : request.body().toString();
        return accounts.authenticatedLinkRequest(request.method(), request.path(), body)
                .thenApply(response -> response.body().isBlank()
                        ? JsonNull.INSTANCE : JsonParser.parseString(response.body()));
    }
}
