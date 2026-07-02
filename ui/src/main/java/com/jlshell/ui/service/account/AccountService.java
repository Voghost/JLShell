package com.jlshell.ui.service.account;

import com.google.gson.Gson;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.ui.config.JlshellDefaults;
import com.jlshell.ui.service.update.UpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 账号服务：登录、注册、会话验证、心跳续签、在线状态上报、修改密码、登出。
 * <p>
 * API 规范见 .docs/client-auth-api.md
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public static final String SETTINGS_BASE_URL = "account.baseUrl";
    public static final String SETTINGS_SYNC_ENABLED = "account.sync.enabled";

    private static final String SETTINGS_TOKEN = "account.authToken";
    private static final String SETTINGS_ACCOUNT_ID = "account.accountId";
    private static final String SETTINGS_USERNAME = "account.username";
    private static final String SETTINGS_EMAIL = "account.email";
    private static final String SETTINGS_ROLE = "account.role";
    private static final String SETTINGS_EXPIRES_AT = "account.expiresAt";
    private static final String SETTINGS_PWD_CHANGE_REQ = "account.passwordChangeRequired";
    private static final String SETTINGS_CONN_COUNT = "account.connectionCount";
    private static final String SETTINGS_TERM_COUNT = "account.terminalCount";

    // 旧版设置键，升级时清理
    private static final String LEGACY_SETTINGS_USER_ID = "account.userId";
    private static final String LEGACY_SETTINGS_DISPLAY_NAME = "account.displayName";

    private static final String DEFAULT_BASE_URL = JlshellDefaults.accountBaseUrl();
    private static final long HEARTBEAT_INTERVAL_MINUTES = 15;
    private static final long NEAR_EXPIRY_MINUTES = 30;
    private static final long REPORT_STATS_INTERVAL_MINUTES = 5;

    private final AppSettingsService appSettings;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private final ScheduledThreadPoolExecutor heartbeatScheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> reportStatsTask;

    /** 当前活跃连接数，由 MainWindow 维护。 */
    private volatile int liveConnectionCount;
    /** 当前活跃终端数，由 MainWindow 维护。 */
    private volatile int liveTerminalCount;

    public AccountService(AppSettingsService appSettings, ExecutorService executor) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.executor = Objects.requireNonNull(executor);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.heartbeatScheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jlshell-account-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.setRemoveOnCancelPolicy(true);
        heartbeatScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    // ── 公开 API ──────────────────────────────────────────────────────────

    /** 登录。username 可以是用户名或邮箱。首次登录不传 captcha。 */
    public CompletableFuture<AccountSession> login(String username, String password) {
        return login(username, password, null, null);
    }

    /** 登录（带验证码）。captchaToken/captchaAnswer 为 null 时不发送。 */
    public CompletableFuture<AccountSession> login(String username, String password,
                                                    String captchaToken, String captchaAnswer) {
        LoginRequest body = new LoginRequest(username, password, captchaToken, captchaAnswer, "desktop");
        return authenticate("/api/v1/account/login", body);
    }

    /** 注册。 */
    public CompletableFuture<AccountSession> register(String username, String email, String password) {
        return authenticate("/api/v1/account/register", new RegisterRequest(username, email, password));
    }

    /** 获取验证码挑战。登录失败后调用。 */
    public CompletableFuture<CaptchaChallenge> fetchCaptcha(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                        endpoint("/api/v1/account/captcha?username=" + URI.create(username).getRawSchemeSpecificPart()))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(),
                            "Captcha request returned HTTP " + response.statusCode());
                }
                CaptchaResponse cr = gson.fromJson(response.body(), CaptchaResponse.class);
                if (cr == null) {
                    return new CaptchaChallenge(false, null, null);
                }
                return new CaptchaChallenge(cr.required, cr.token, cr.question);
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Captcha request failed", e);
            }
        }, executor);
    }

    /** 用已保存的 token 验证会话（启动时调用）。返回 null 表示 token 无效。 */
    public CompletableFuture<AccountSession> validateSession() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = appSettings.get(SETTINGS_TOKEN, "");
                if (token.isBlank()) {
                    return null;
                }
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/me"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 || response.statusCode() == 404) {
                    clearSession();
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(),
                            "Account validation returned HTTP " + response.statusCode());
                }
                MeResponse me = gson.fromJson(response.body(), MeResponse.class);
                if (me == null) {
                    clearSession();
                    return null;
                }
                AccountSession session = new AccountSession(
                        defaultString(me.id()),
                        defaultString(me.username()),
                        defaultString(me.email()),
                        defaultString(me.role()),
                        token,
                        appSettings.get(SETTINGS_EXPIRES_AT, ""),
                        me.passwordChangeRequired(),
                        me.connectionCount(),
                        me.terminalCount()
                );
                persist(session);
                startHeartbeat();
                startReportStats();
                return session;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Session validation failed", e);
            }
        }, executor);
    }

    /** 心跳续签。返回 null 表示 token 已失效（会话已清除）。 */
    public CompletableFuture<AccountSession> heartbeat() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = appSettings.get(SETTINGS_TOKEN, "");
                if (token.isBlank()) {
                    stopHeartbeat();
                    return null;
                }
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/heartbeat"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 || response.statusCode() == 404) {
                    clearSession();
                    stopHeartbeat();
                    stopReportStats();
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(),
                            "Heartbeat returned HTTP " + response.statusCode());
                }
                AuthResponse authResponse = gson.fromJson(response.body(), AuthResponse.class);
                if (authResponse == null || blank(authResponse.token()) || authResponse.account() == null) {
                    return null;
                }
                AccountInfo info = authResponse.account();
                AccountSession session = new AccountSession(
                        defaultString(info.id()),
                        defaultString(info.username()),
                        defaultString(info.email()),
                        defaultString(info.role()),
                        authResponse.token(),
                        defaultString(authResponse.expiresAt()),
                        info.passwordChangeRequired(),
                        info.connectionCount(),
                        info.terminalCount()
                );
                persist(session);
                return session;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                // 网络故障：保留 token，下次重试
                log.warn("Heartbeat failed (will retry): {}", e.getMessage());
                return null;
            }
        }, executor);
    }

    /** 上报在线状态（连接数/终端数）。 */
    public CompletableFuture<AccountSession> reportStats(int connectionCount, int terminalCount) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = appSettings.get(SETTINGS_TOKEN, "");
                if (token.isBlank()) {
                    return null;
                }
                String body = gson.toJson(new ReportStatsRequest(
                        Math.max(0, connectionCount), Math.max(0, terminalCount)));
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/report-stats"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 || response.statusCode() == 404) {
                    clearSession();
                    stopHeartbeat();
                    stopReportStats();
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Report-stats returned HTTP {}", response.statusCode());
                    return null;
                }
                MeResponse me = gson.fromJson(response.body(), MeResponse.class);
                if (me == null) return null;
                AccountSession session = new AccountSession(
                        defaultString(me.id()),
                        defaultString(me.username()),
                        defaultString(me.email()),
                        defaultString(me.role()),
                        token,
                        appSettings.get(SETTINGS_EXPIRES_AT, ""),
                        me.passwordChangeRequired(),
                        me.connectionCount(),
                        me.terminalCount()
                );
                persist(session);
                return session;
            } catch (Exception e) {
                log.warn("Report-stats failed: {}", e.getMessage());
                return null;
            }
        }, executor);
    }

    /** 修改密码。成功后更新本地缓存的 account 信息。 */
    public CompletableFuture<AccountSession> changePassword(String oldPassword, String newPassword) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = appSettings.get(SETTINGS_TOKEN, "");
                if (token.isBlank()) {
                    throw new AccountException("Not signed in", null);
                }
                String body = gson.toJson(new ChangePasswordRequest(oldPassword, newPassword));
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/password"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(),
                            "Change password returned HTTP " + response.statusCode());
                }
                MeResponse me = gson.fromJson(response.body(), MeResponse.class);
                if (me == null) {
                    throw new IOException("Change password did not return account info");
                }
                AccountSession session = new AccountSession(
                        defaultString(me.id()),
                        defaultString(me.username()),
                        defaultString(me.email()),
                        defaultString(me.role()),
                        token,
                        appSettings.get(SETTINGS_EXPIRES_AT, ""),
                        me.passwordChangeRequired(),
                        me.connectionCount(),
                        me.terminalCount()
                );
                persist(session);
                return session;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Change password failed", e);
            }
        }, executor);
    }

    /** 服务端登出 + 清除本地会话。无论网络是否成功，本地会话都会被清除。 */
    public CompletableFuture<Void> logout() {
        stopHeartbeat();
        stopReportStats();
        return CompletableFuture.runAsync(() -> {
            try {
                String token = appSettings.get(SETTINGS_TOKEN, "");
                if (!token.isBlank()) {
                    HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/logout"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    try {
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        // 网络故障：仍然清除本地 token
                        log.debug("Logout network call failed (clearing local state anyway)", e);
                    }
                }
            } catch (Exception e) {
                log.debug("Logout failed (clearing local state anyway)", e);
            }
            clearSession();
        }, executor);
    }

    /** 获取当前会话（从本地设置读取，不验证）。 */
    public Optional<AccountSession> currentSession() {
        String token = appSettings.get(SETTINGS_TOKEN, "");
        if (token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AccountSession(
                appSettings.get(SETTINGS_ACCOUNT_ID, ""),
                appSettings.get(SETTINGS_USERNAME, ""),
                appSettings.get(SETTINGS_EMAIL, ""),
                appSettings.get(SETTINGS_ROLE, ""),
                token,
                appSettings.get(SETTINGS_EXPIRES_AT, ""),
                Boolean.parseBoolean(appSettings.get(SETTINGS_PWD_CHANGE_REQ, "false")),
                Integer.parseInt(appSettings.get(SETTINGS_CONN_COUNT, "0")),
                Integer.parseInt(appSettings.get(SETTINGS_TERM_COUNT, "0"))
        ));
    }

    public boolean isSignedIn() {
        return currentSession().isPresent();
    }

    public String baseUrl() {
        String configured = appSettings.get(SETTINGS_BASE_URL, "");
        String normalized = UpdateService.normalizeBaseUrl(configured, "");
        if (!normalized.isBlank()) {
            return normalized;
        }
        return UpdateService.normalizeBaseUrl(
                appSettings.get(UpdateService.SETTINGS_BASE_URL, ""),
                DEFAULT_BASE_URL);
    }

    public boolean syncEnabled() {
        return Boolean.parseBoolean(appSettings.get(SETTINGS_SYNC_ENABLED, "false"));
    }

    /** 更新当前活跃连接/终端数，并立即上报。由 MainWindow 调用。 */
    public void updateLiveStats(int connectionCount, int terminalCount) {
        this.liveConnectionCount = connectionCount;
        this.liveTerminalCount = terminalCount;
        if (isSignedIn()) {
            reportStats(connectionCount, terminalCount);
        }
    }

    /** 关闭调度器。在 AppContext.close() 中调用。 */
    public void shutdown() {
        stopHeartbeat();
        stopReportStats();
        heartbeatScheduler.shutdownNow();
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    private CompletableFuture<AccountSession> authenticate(String path, Object requestBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(),
                            "Account API returned HTTP " + response.statusCode());
                }
                AuthResponse authResponse = gson.fromJson(response.body(), AuthResponse.class);
                if (authResponse == null || blank(authResponse.token()) || authResponse.account() == null) {
                    throw new IOException("Account API did not return a valid auth response");
                }
                AccountInfo info = authResponse.account();
                AccountSession session = new AccountSession(
                        defaultString(info.id()),
                        defaultString(info.username()),
                        defaultString(info.email()),
                        defaultString(info.role()),
                        authResponse.token(),
                        defaultString(authResponse.expiresAt()),
                        info.passwordChangeRequired(),
                        info.connectionCount(),
                        info.terminalCount()
                );
                persist(session);
                startHeartbeat();
                startReportStats();
                return session;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Account authentication failed", e);
            }
        }, executor);
    }

    private void persist(AccountSession session) {
        appSettings.set(SETTINGS_TOKEN, session.token());
        appSettings.set(SETTINGS_ACCOUNT_ID, session.id());
        appSettings.set(SETTINGS_USERNAME, session.username());
        appSettings.set(SETTINGS_EMAIL, session.email());
        appSettings.set(SETTINGS_ROLE, session.role());
        appSettings.set(SETTINGS_EXPIRES_AT, session.expiresAt());
        appSettings.set(SETTINGS_PWD_CHANGE_REQ, String.valueOf(session.passwordChangeRequired()));
        appSettings.set(SETTINGS_CONN_COUNT, String.valueOf(session.connectionCount()));
        appSettings.set(SETTINGS_TERM_COUNT, String.valueOf(session.terminalCount()));
    }

    private void clearSession() {
        appSettings.remove(SETTINGS_TOKEN);
        appSettings.remove(SETTINGS_ACCOUNT_ID);
        appSettings.remove(SETTINGS_USERNAME);
        appSettings.remove(SETTINGS_EMAIL);
        appSettings.remove(SETTINGS_ROLE);
        appSettings.remove(SETTINGS_EXPIRES_AT);
        appSettings.remove(SETTINGS_PWD_CHANGE_REQ);
        appSettings.remove(SETTINGS_CONN_COUNT);
        appSettings.remove(SETTINGS_TERM_COUNT);
        // 清理旧版键
        appSettings.remove(LEGACY_SETTINGS_USER_ID);
        appSettings.remove(LEGACY_SETTINGS_DISPLAY_NAME);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (tokenNearExpiry()) {
                    heartbeat().whenComplete((session, error) -> {
                        if (error != null) {
                            log.warn("Scheduled heartbeat error: {}", error.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("Heartbeat scheduler error: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MINUTES, HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void startReportStats() {
        stopReportStats();
        reportStatsTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (isSignedIn()) {
                    reportStats(liveConnectionCount, liveTerminalCount);
                }
            } catch (Exception e) {
                log.warn("Report-stats scheduler error: {}", e.getMessage());
            }
        }, REPORT_STATS_INTERVAL_MINUTES, REPORT_STATS_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void stopReportStats() {
        ScheduledFuture<?> task = reportStatsTask;
        if (task != null) {
            task.cancel(false);
            reportStatsTask = null;
        }
    }

    private boolean tokenNearExpiry() {
        String expiresAt = appSettings.get(SETTINGS_EXPIRES_AT, "");
        if (expiresAt.isBlank()) return false;
        try {
            Instant expiry = Instant.parse(expiresAt);
            return expiry.isBefore(Instant.now().plus(Duration.ofMinutes(NEAR_EXPIRY_MINUTES)));
        } catch (Exception e) {
            return false;
        }
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

    // ── 内部数据模型 ──────────────────────────────────────────────────────

    private record LoginRequest(String username, String password,
                                String captchaToken, String captchaAnswer, String clientType) {
        /** Gson 序列化时忽略 null 的 captcha 字段。 */
        LoginRequest {
            if (captchaToken == null && captchaAnswer == null) {
                // Gson 会跳过 null 字段，这正是我们想要的
            }
        }
    }

    private record RegisterRequest(String username, String email, String password) {}

    private record AuthResponse(String token, String expiresAt, AccountInfo account) {}

    private record AccountInfo(String id, String username, String email, String role,
                               boolean passwordChangeRequired, int connectionCount, int terminalCount) {}

    private record MeResponse(String id, String username, String email, String role,
                              boolean passwordChangeRequired, int connectionCount, int terminalCount) {}

    private record CaptchaResponse(boolean required, String token, String question) {}

    private record ReportStatsRequest(int connectionCount, int terminalCount) {}

    private record ChangePasswordRequest(String oldPassword, String newPassword) {}

    // ── 公开数据模型 ──────────────────────────────────────────────────────

    /** 已认证的会话。 */
    public record AccountSession(
            String id, String username, String email, String role,
            String token, String expiresAt,
            boolean passwordChangeRequired, int connectionCount, int terminalCount
    ) {}

    /** 验证码挑战。 */
    public record CaptchaChallenge(boolean required, String token, String question) {}

    /** 账号操作通用异常。 */
    public static class AccountException extends RuntimeException {
        public AccountException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** HTTP 错误响应，携带状态码以便 UI 区分 401/403/409 等。 */
    public static class AccountHttpException extends AccountException {
        private final int statusCode;
        public AccountHttpException(int statusCode, String message) {
            super(message, null);
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
    }
}
