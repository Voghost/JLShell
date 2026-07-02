package com.jlshell.ui.service.account;

import com.google.gson.Gson;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.ui.service.update.UpdateService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class AccountService {

    public static final String SETTINGS_BASE_URL = "account.baseUrl";
    public static final String SETTINGS_SYNC_ENABLED = "account.sync.enabled";
    private static final String SETTINGS_TOKEN = "account.authToken";
    private static final String SETTINGS_USER_ID = "account.userId";
    private static final String SETTINGS_EMAIL = "account.email";
    private static final String SETTINGS_DISPLAY_NAME = "account.displayName";
    private static final String DEFAULT_BASE_URL = UpdateService.DEFAULT_BASE_URL;

    private final AppSettingsService appSettings;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public AccountService(AppSettingsService appSettings, ExecutorService executor) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.executor = Objects.requireNonNull(executor);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<AccountSession> login(String email, String password) {
        return authenticate("/api/v1/auth/login", new AuthRequest(email, password, null));
    }

    public CompletableFuture<AccountSession> register(String email, String password, String displayName) {
        return authenticate("/api/v1/auth/register", new AuthRequest(email, password, displayName));
    }

    public Optional<AccountSession> currentSession() {
        String token = appSettings.get(SETTINGS_TOKEN, "");
        if (token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AccountSession(
                appSettings.get(SETTINGS_USER_ID, ""),
                appSettings.get(SETTINGS_EMAIL, ""),
                appSettings.get(SETTINGS_DISPLAY_NAME, ""),
                token
        ));
    }

    public boolean isSignedIn() {
        return currentSession().isPresent();
    }

    public void logout() {
        appSettings.remove(SETTINGS_TOKEN);
        appSettings.remove(SETTINGS_USER_ID);
        appSettings.remove(SETTINGS_EMAIL);
        appSettings.remove(SETTINGS_DISPLAY_NAME);
    }

    public String baseUrl() {
        String configured = appSettings.get(SETTINGS_BASE_URL, "");
        if (!configured.isBlank()) {
            return configured;
        }
        return appSettings.get(UpdateService.SETTINGS_BASE_URL, DEFAULT_BASE_URL);
    }

    public boolean syncEnabled() {
        return Boolean.parseBoolean(appSettings.get(SETTINGS_SYNC_ENABLED, "false"));
    }

    private CompletableFuture<AccountSession> authenticate(String path, AuthRequest authRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(authRequest)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Account API returned HTTP " + response.statusCode());
                }
                AuthResponse authResponse = gson.fromJson(response.body(), AuthResponse.class);
                if (authResponse == null || blank(authResponse.token())) {
                    throw new IOException("Account API did not return a token");
                }
                AccountSession session = new AccountSession(
                        defaultString(authResponse.userId()),
                        defaultString(authResponse.email()),
                        defaultString(authResponse.displayName()),
                        authResponse.token()
                );
                persist(session);
                return session;
            } catch (Exception e) {
                throw new AccountException("Account authentication failed", e);
            }
        }, executor);
    }

    private void persist(AccountSession session) {
        appSettings.set(SETTINGS_TOKEN, session.token());
        appSettings.set(SETTINGS_USER_ID, session.userId());
        appSettings.set(SETTINGS_EMAIL, session.email());
        appSettings.set(SETTINGS_DISPLAY_NAME, session.displayName());
    }

    private URI endpoint(String path) {
        String base = baseUrl().strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record AuthRequest(String email, String password, String displayName) {}

    private record AuthResponse(String userId, String email, String displayName, String token) {}

    public record AccountSession(String userId, String email, String displayName, String token) {}

    public static class AccountException extends RuntimeException {
        public AccountException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
