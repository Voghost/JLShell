package com.jlshell.ui.service.account;

import com.google.gson.Gson;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.SecureSettingsService;
import com.jlshell.ui.config.JlshellDefaults;
import com.jlshell.ui.service.update.UpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 账号服务：登录、注册、会话验证、心跳续签、在线状态上报、修改密码、登出。
 * <p>
 * API 规范见 .docs/client-auth-api.md
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public static final String SETTINGS_BASE_URL = "account.baseUrl";
    public static final String SETTINGS_SYNC_ENABLED = "account.sync.enabled";

    private static final String SECURE_TOKEN_KEY = "account.authToken";
    private static final String LEGACY_SETTINGS_TOKEN = "account.authToken";
    private static final String SETTINGS_ACCOUNT_ID = "account.accountId";
    private static final String SETTINGS_USERNAME = "account.username";
    private static final String SETTINGS_EMAIL = "account.email";
    private static final String SETTINGS_ROLE = "account.role";
    private static final String SETTINGS_EXPIRES_AT = "account.expiresAt";
    private static final String SETTINGS_PWD_CHANGE_REQ = "account.passwordChangeRequired";
    private static final String SETTINGS_CONN_COUNT = "account.connectionCount";
    private static final String SETTINGS_TERM_COUNT = "account.terminalCount";
    private static final String SETTINGS_HIST_DEVICE_COUNT = "account.historicalDeviceCount";
    private static final String SETTINGS_DEVICE_ID = "device.id";

    // 旧版设置键，升级时清理
    private static final String LEGACY_SETTINGS_USER_ID = "account.userId";
    private static final String LEGACY_SETTINGS_DISPLAY_NAME = "account.displayName";

    private static final String DEFAULT_BASE_URL = JlshellDefaults.accountBaseUrl();
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(15);
    private static final Duration REPORT_STATS_INTERVAL = Duration.ofMinutes(5);
    private static final int MAX_PLUGIN_RESPONSE_BYTES = 1024 * 1024;

    private final AppSettingsService appSettings;
    private final SecureSettingsService secureSettings;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final Duration heartbeatInterval;
    private final Duration reportStatsInterval;
    private final DesktopBrowserLogin browserLogin;

    private final ScheduledThreadPoolExecutor heartbeatScheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> reportStatsTask;
    private volatile BrowserLoginAttempt activeBrowserLogin;

    /** 当前活跃连接数，由 MainWindow 维护。 */
    private volatile int liveConnectionCount;

    public AccountService(AppSettingsService appSettings, SecureSettingsService secureSettings,
                          ExecutorService executor) {
        this(appSettings, secureSettings, executor, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .executor(executor)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                newScheduler(), HEARTBEAT_INTERVAL, REPORT_STATS_INTERVAL,
                new DesktopBrowserLogin(executor));
    }

    AccountService(AppSettingsService appSettings, SecureSettingsService secureSettings,
                   ExecutorService executor, HttpClient httpClient,
                   ScheduledThreadPoolExecutor scheduler, Duration heartbeatInterval,
                   Duration reportStatsInterval, DesktopBrowserLogin browserLogin) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.secureSettings = Objects.requireNonNull(secureSettings);
        this.executor = Objects.requireNonNull(executor);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.heartbeatScheduler = Objects.requireNonNull(scheduler);
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        this.reportStatsInterval = requirePositive(reportStatsInterval, "reportStatsInterval");
        this.browserLogin = Objects.requireNonNull(browserLogin);
        heartbeatScheduler.setRemoveOnCancelPolicy(true);
        heartbeatScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        migrateLegacyToken();
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        return new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jlshell-account-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    // ── 公开 API ──────────────────────────────────────────────────────────

    /** 登录。username 可以是用户名或邮箱。首次登录不传 captcha。 */
    public CompletableFuture<AccountSession> login(String username, String password) {
        return login(username, password, null, null);
    }

    /** 登录（带验证码）。captchaToken/captchaAnswer 为 null 时不发送。 */
    public CompletableFuture<AccountSession> login(String username, String password,
                                                    String captchaToken, String captchaAnswer) {
        LoginRequest body = new LoginRequest(username, password,
                captchaToken, captchaAnswer, "desktop",
                ensureDeviceId(), getDeviceName());
        return authenticate("/api/v1/account/login", body);
    }

    /** 使用系统浏览器完成 Authorization Code + PKCE 登录。 */
    public synchronized BrowserLoginAttempt loginWithBrowser() {
        if (activeBrowserLogin != null && !activeBrowserLogin.completion().isDone()) {
            return activeBrowserLogin;
        }
        try {
            DesktopBrowserLogin.Attempt transport = browserLogin.start(
                    URI.create(baseUrl()), ensureDeviceId(), getDeviceName());
            CompletableFuture<AccountSession> completion = transport.completion()
                    .thenCompose(exchange -> authenticate("/api/v1/desktop-token",
                            new DesktopTokenRequest(exchange.code(), exchange.verifier(), exchange.redirectUri())));
            BrowserLoginAttempt attempt = new BrowserLoginAttempt(
                    transport.authorizationUri(), transport.browserOpened(), completion,
                    transport::openBrowser, transport::cancel);
            activeBrowserLogin = attempt;
            completion.whenComplete((session, error) -> {
                synchronized (AccountService.this) {
                    if (activeBrowserLogin == attempt) activeBrowserLogin = null;
                }
            });
            return attempt;
        } catch (Exception error) {
            throw new AccountException("Failed to start browser login", error);
        }
    }

    /** 注册（带邮箱验证码）。 */
    public CompletableFuture<AccountSession> register(String username, String email,
                                                      String password, String verificationCode) {
        return authenticate("/api/v1/account/register",
                new RegisterRequest(username, email, password, verificationCode));
    }

    /** 发送邮箱验证码。captchaToken/captchaAnswer 来自注册前的 captcha 挑战。 */
    public CompletableFuture<Void> sendVerification(String email,
                                                     String captchaToken, String captchaAnswer) {
        return CompletableFuture.runAsync(() -> {
            try {
                String body = gson.toJson(new SendVerificationRequest(email, captchaToken, captchaAnswer));
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/send-verification"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw parseError(response.statusCode(), response.body());
                }
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Send verification failed", e);
            }
        }, executor);
    }

    /** 获取验证码挑战（登录失败后调用）。 */
    public CompletableFuture<CaptchaChallenge> fetchCaptcha(String username) {
        return fetchCaptcha(username, null);
    }

    /** 获取验证码挑战。purpose="register" 时始终生成验证码。 */
    public CompletableFuture<CaptchaChallenge> fetchCaptcha(String username, String purpose) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder url = new StringBuilder("/api/v1/account/captcha?username=");
                url.append(URI.create(username).getRawSchemeSpecificPart());
                if (purpose != null && !purpose.isBlank()) {
                    url.append("&purpose=").append(URI.create(purpose).getRawSchemeSpecificPart());
                }
                HttpRequest request = HttpRequest.newBuilder(endpoint(url.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AccountHttpException(response.statusCode(), null,
                            "Captcha request returned HTTP " + response.statusCode());
                }
                CaptchaResponse cr = gson.fromJson(response.body(), CaptchaResponse.class);
                if (cr == null) {
                    return new CaptchaChallenge(false, null, null, null);
                }
                return new CaptchaChallenge(cr.required, cr.token, cr.question, cr.imageBase64);
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
                String token = token();
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
                    throw parseError(response.statusCode(), response.body());
                }
                MeResponse me = gson.fromJson(response.body(), MeResponse.class);
                if (me == null) {
                    throw new IOException("Account API did not return valid account information");
                }
                AccountSession session = buildSessionFromMe(me, token);
                if (!replaceSessionIfTokenMatches(token, session)) {
                    return null;
                }
                startHeartbeat();
                startReportStats();
                reportCurrentStats("restored session");
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
                String token = token();
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
                    throw parseError(response.statusCode(), response.body());
                }
                AuthResponse authResponse = gson.fromJson(response.body(), AuthResponse.class);
                if (authResponse == null || blank(authResponse.token()) || authResponse.account() == null) {
                    return null;
                }
                AccountSession session = buildSessionFromAuth(authResponse);
                return replaceSessionIfTokenMatches(token, session) ? session : null;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                // 网络故障：保留 token，下次重试
                log.warn("Heartbeat failed (will retry): {}", e.getMessage());
                return null;
            }
        }, executor);
    }

    /** 上报在线状态（连接数 + 设备 ID）。 */
    public CompletableFuture<AccountSession> reportStats(int connectionCount) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = token();
                if (token.isBlank()) {
                    return null;
                }
                String accountId = appSettings.get(SETTINGS_ACCOUNT_ID, "");
                String body = gson.toJson(new ReportStatsRequest(
                        Math.max(0, connectionCount), ensureDeviceId()));
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
                return updateAccountFromStats(me, accountId);
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
                String token = token();
                if (token.isBlank()) {
                    throw new AccountException("Not signed in", null);
                }
                String accountId = appSettings.get(SETTINGS_ACCOUNT_ID, "");
                String body = gson.toJson(new ChangePasswordRequest(oldPassword, newPassword));
                HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/password"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw parseError(response.statusCode(), response.body());
                }
                MeResponse me = gson.fromJson(response.body(), MeResponse.class);
                if (me == null) {
                    throw new IOException("Change password did not return account info");
                }
                return updateAccountFromStats(me, accountId);
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
                String token = token();
                if (!token.isBlank()) {
                    HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/account/logout"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    try {
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
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
        String token = token();
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
                Integer.parseInt(appSettings.get(SETTINGS_TERM_COUNT, "0")),
                Integer.parseInt(appSettings.get(SETTINGS_HIST_DEVICE_COUNT, "0"))
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

    /** 稳定的桌面设备 ID；可供宿主插件网关匹配服务器上的设备记录。 */
    public String deviceId() {
        return ensureDeviceId();
    }

    /**
     * 使用宿主保存的账号令牌代发 Link 控制平面请求。
     *
     * <p>此方法不会返回令牌，且仅允许 Link API 与设备列表端点，避免插件将账号会话
     * 扩展为任意网站请求代理。</p>
     */
    public CompletableFuture<AuthenticatedResponse> authenticatedLinkRequest(
            String method, String apiPath, String jsonBody) {
        return CompletableFuture.supplyAsync(() -> {
            String requestMethod = requireLinkMethod(method);
            String requestPath = requireLinkPath(apiPath);
            try {
                String currentToken = token();
                if (currentToken.isBlank()) {
                    throw new AccountException("Not signed in", null);
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(requestPath))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + currentToken)
                        .header("User-Agent", "JLShell/host-account-gateway");
                if (jsonBody == null) {
                    builder.method(requestMethod, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                            .method(requestMethod, HttpRequest.BodyPublishers.ofString(jsonBody));
                }
                HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.body().length > MAX_PLUGIN_RESPONSE_BYTES) {
                    throw new IOException("Account API response is too large");
                }
                String payload = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                if (response.statusCode() == 401 || response.statusCode() == 404) {
                    clearSession();
                    stopHeartbeat();
                    stopReportStats();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw parseError(response.statusCode(), payload);
                }
                return new AuthenticatedResponse(response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse("application/json"), payload);
            } catch (AccountException error) {
                throw error;
            } catch (Exception error) {
                throw new AccountException("Host account request failed", error);
            }
        }, executor);
    }

    public boolean syncEnabled() {
        return Boolean.parseBoolean(appSettings.get(SETTINGS_SYNC_ENABLED, "false"));
    }

    /** 更新当前活跃连接数，并立即上报。由 MainWindow 调用。 */
    public void updateLiveStats(int connectionCount) {
        setLiveConnectionCount(connectionCount);
        if (isSignedIn()) {
            reportCurrentStats("SSH session state change");
        }
    }

    /** 只更新本机缓存，供启动验证前设置真实 SSH 连接数。 */
    public void setLiveConnectionCount(int connectionCount) {
        this.liveConnectionCount = Math.max(0, connectionCount);
    }

    /** 关闭调度器。在 AppContext.close() 中调用。 */
    public void shutdown() {
        BrowserLoginAttempt attempt = activeBrowserLogin;
        if (attempt != null) attempt.cancel();
        stopHeartbeat();
        stopReportStats();
        heartbeatScheduler.shutdownNow();
    }

    // ── 设备 ID ──────────────────────────────────────────────────────────

    private void migrateLegacyToken() {
        String legacy = appSettings.get(LEGACY_SETTINGS_TOKEN, "");
        try {
            if (secureSettings.get(SECURE_TOKEN_KEY).isEmpty() && !legacy.isBlank()) {
                secureSettings.set(SECURE_TOKEN_KEY, legacy);
            }
            if (!legacy.isBlank()) {
                appSettings.remove(LEGACY_SETTINGS_TOKEN);
            }
        } catch (RuntimeException error) {
            log.warn("Failed to migrate encrypted account token: {}", error.getMessage());
        }
    }

    private String token() {
        try {
            return secureSettings.get(SECURE_TOKEN_KEY).orElse("");
        } catch (RuntimeException error) {
            log.warn("Encrypted account token is unreadable and will be cleared: {}", error.getMessage());
            secureSettings.remove(SECURE_TOKEN_KEY);
            return "";
        }
    }

    /** 获取或生成设备 ID。首次启动时生成 UUID 并持久化，之后永不改变。 */
    private String ensureDeviceId() {
        String existing = appSettings.get(SETTINGS_DEVICE_ID, "");
        if (!existing.isBlank()) {
            return existing;
        }
        String newId = UUID.randomUUID().toString();
        appSettings.set(SETTINGS_DEVICE_ID, newId);
        return newId;
    }

    /** 获取设备名（主机名）。 */
    private static String getDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    private AccountSession buildSessionFromAuth(AuthResponse authResponse) {
        AccountInfo info = authResponse.account();
        return new AccountSession(
                defaultString(info.id()),
                defaultString(info.username()),
                defaultString(info.email()),
                defaultString(info.role()),
                authResponse.token(),
                defaultString(authResponse.expiresAt()),
                info.passwordChangeRequired(),
                info.connectionCount(),
                info.terminalCount(),
                info.historicalDeviceCount()
        );
    }

    private AccountSession buildSessionFromMe(MeResponse me, String token) {
        return new AccountSession(
                defaultString(me.id()),
                defaultString(me.username()),
                defaultString(me.email()),
                defaultString(me.role()),
                token,
                appSettings.get(SETTINGS_EXPIRES_AT, ""),
                me.passwordChangeRequired(),
                me.connectionCount(),
                me.terminalCount(),
                me.historicalDeviceCount()
        );
    }

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
                    throw parseError(response.statusCode(), response.body());
                }
                AuthResponse authResponse = gson.fromJson(response.body(), AuthResponse.class);
                if (authResponse == null || blank(authResponse.token()) || authResponse.account() == null) {
                    throw new IOException("Account API did not return a valid auth response");
                }
                AccountSession session = buildSessionFromAuth(authResponse);
                persist(session);
                startHeartbeat();
                startReportStats();
                reportCurrentStats("authentication");
                return session;
            } catch (AccountHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new AccountException("Account authentication failed", e);
            }
        }, executor);
    }

    private synchronized void persist(AccountSession session) {
        appSettings.set(SETTINGS_ACCOUNT_ID, session.id());
        appSettings.set(SETTINGS_USERNAME, session.username());
        appSettings.set(SETTINGS_EMAIL, session.email());
        appSettings.set(SETTINGS_ROLE, session.role());
        appSettings.set(SETTINGS_EXPIRES_AT, session.expiresAt());
        appSettings.set(SETTINGS_PWD_CHANGE_REQ, String.valueOf(session.passwordChangeRequired()));
        appSettings.set(SETTINGS_CONN_COUNT, String.valueOf(session.connectionCount()));
        appSettings.set(SETTINGS_TERM_COUNT, String.valueOf(session.terminalCount()));
        appSettings.set(SETTINGS_HIST_DEVICE_COUNT, String.valueOf(session.historicalDeviceCount()));
        // Token 最后替换；其他字段全部成功写入后，新会话才对读取方可见。
        secureSettings.set(SECURE_TOKEN_KEY, session.token());
    }

    private synchronized boolean replaceSessionIfTokenMatches(String expectedToken, AccountSession session) {
        if (!expectedToken.equals(token())) {
            return false;
        }
        persist(session);
        return true;
    }

    /** 统计接口不返回新 Token，始终保留心跳可能刚刚替换的当前 Token。 */
    private synchronized AccountSession updateAccountFromStats(MeResponse me, String expectedAccountId) {
        String currentToken = token();
        String currentAccountId = appSettings.get(SETTINGS_ACCOUNT_ID, "");
        if (currentToken.isBlank()
                || !Objects.equals(expectedAccountId, currentAccountId)
                || !Objects.equals(currentAccountId, defaultString(me.id()))) {
            return null;
        }
        AccountSession session = buildSessionFromMe(me, currentToken);
        persist(session);
        return session;
    }

    private synchronized void clearSession() {
        secureSettings.remove(SECURE_TOKEN_KEY);
        appSettings.remove(LEGACY_SETTINGS_TOKEN);
        appSettings.remove(SETTINGS_ACCOUNT_ID);
        appSettings.remove(SETTINGS_USERNAME);
        appSettings.remove(SETTINGS_EMAIL);
        appSettings.remove(SETTINGS_ROLE);
        appSettings.remove(SETTINGS_EXPIRES_AT);
        appSettings.remove(SETTINGS_PWD_CHANGE_REQ);
        appSettings.remove(SETTINGS_CONN_COUNT);
        appSettings.remove(SETTINGS_TERM_COUNT);
        appSettings.remove(SETTINGS_HIST_DEVICE_COUNT);
        // 清理旧版键
        appSettings.remove(LEGACY_SETTINGS_USER_ID);
        appSettings.remove(LEGACY_SETTINGS_DISPLAY_NAME);
        // 注意：不清除 SETTINGS_DEVICE_ID，设备 ID 应跨登录会话持久保留
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                heartbeat().whenComplete((session, error) -> {
                    if (error != null) {
                        log.warn("Scheduled heartbeat error: {}", error.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Heartbeat scheduler error: {}", e.getMessage());
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
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
                    reportCurrentStats("scheduled report");
                }
            } catch (Exception e) {
                log.warn("Report-stats scheduler error: {}", e.getMessage());
            }
        }, reportStatsInterval.toMillis(), reportStatsInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void stopReportStats() {
        ScheduledFuture<?> task = reportStatsTask;
        if (task != null) {
            task.cancel(false);
            reportStatsTask = null;
        }
    }

    private void reportCurrentStats(String trigger) {
        reportStats(liveConnectionCount).whenComplete((session, error) -> {
            if (error != null) {
                log.warn("Account stats report failed after {}: {}", trigger, error.getMessage());
            }
        });
    }

    private URI endpoint(String path) {
        String base = baseUrl().strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static String requireLinkMethod(String method) {
        String value = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("GET", "POST", "PUT", "DELETE").contains(value)) {
            throw new IllegalArgumentException("Unsupported host account request method");
        }
        return value;
    }

    private static String requireLinkPath(String path) {
        try {
            URI value = URI.create(path == null ? "" : path);
            String rawPath = value.getRawPath();
            boolean permitted = rawPath != null && (rawPath.startsWith("/api/v1/link/")
                    || "/api/v1/account/devices".equals(rawPath));
            if (value.isAbsolute() || value.getRawAuthority() != null || value.getRawQuery() != null
                    || value.getRawFragment() != null || !permitted) {
                throw new IllegalArgumentException();
            }
            return rawPath;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Only supported JLShell Link API paths are allowed");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    /** 从 HTTP 响应体解析错误 JSON，提取 error code 和 message。 */
    private static AccountHttpException parseError(int statusCode, String body) {
        String errorCode = null;
        String message = "HTTP " + statusCode;
        if (body != null && !body.isBlank()) {
            try {
                ErrorResponse err = new Gson().fromJson(body, ErrorResponse.class);
                if (err != null) {
                    if (err.error != null && !err.error.isBlank()) errorCode = err.error;
                    if (err.message != null && !err.message.isBlank()) message = err.message;
                }
            } catch (Exception ignored) {}
        }
        return new AccountHttpException(statusCode, errorCode, message);
    }

    private record ErrorResponse(String error, String message) {}

    // ── 内部数据模型 ──────────────────────────────────────────────────────

    private record LoginRequest(String username, String password,
                                String captchaToken, String captchaAnswer,
                                String clientType, String deviceId, String deviceName) {}

    private record DesktopTokenRequest(String code, String codeVerifier, String redirectUri) {}

    private record RegisterRequest(String username, String email, String password,
                                   String verificationCode) {}

    private record SendVerificationRequest(String email, String captchaToken,
                                           String captchaAnswer) {}

    private record AuthResponse(String token, String expiresAt, AccountInfo account) {}

    private record AccountInfo(String id, String username, String email, String role,
                               boolean passwordChangeRequired,
                               int connectionCount, int terminalCount,
                               int historicalDeviceCount) {}

    private record MeResponse(String id, String username, String email, String role,
                              boolean passwordChangeRequired,
                              int connectionCount, int terminalCount,
                              int historicalDeviceCount) {}

    private record CaptchaResponse(boolean required, String token, String question,
                                   String imageBase64) {}

    private record ReportStatsRequest(int connectionCount, String deviceId) {}

    private record ChangePasswordRequest(String oldPassword, String newPassword) {}

    // ── 公开数据模型 ──────────────────────────────────────────────────────

    /** 已认证的会话。 */
    public record AccountSession(
            String id, String username, String email, String role,
            String token, String expiresAt,
            boolean passwordChangeRequired,
            int connectionCount, int terminalCount,
            int historicalDeviceCount
    ) {}

    /** 已认证宿主网关的响应；不包含请求令牌。 */
    public record AuthenticatedResponse(int statusCode, String contentType, String body) {}

    /** 验证码挑战。imageBase64 为 data URI（如 "data:image/png;base64,..."），question 为文本验证码（两者互斥）。 */
    public record CaptchaChallenge(boolean required, String token, String question,
                                   String imageBase64) {}

    /** 正在进行的浏览器登录，可用于重新打开授权页、取消或监听最终账号会话。 */
    public static final class BrowserLoginAttempt {
        private final URI authorizationUri;
        private volatile boolean browserOpened;
        private final CompletableFuture<AccountSession> completion;
        private final BooleanSupplier browserOpener;
        private final Runnable canceller;

        private BrowserLoginAttempt(URI authorizationUri, boolean browserOpened,
                                    CompletableFuture<AccountSession> completion,
                                    BooleanSupplier browserOpener, Runnable canceller) {
            this.authorizationUri = authorizationUri;
            this.browserOpened = browserOpened;
            this.completion = completion;
            this.browserOpener = browserOpener;
            this.canceller = canceller;
        }

        public URI authorizationUri() { return authorizationUri; }

        public boolean browserOpened() { return browserOpened; }

        public CompletableFuture<AccountSession> completion() { return completion; }

        public boolean openBrowser() {
            browserOpened = browserOpener.getAsBoolean() || browserOpened;
            return browserOpened;
        }

        public void cancel() { canceller.run(); }
    }

    /** 账号操作通用异常。 */
    public static class AccountException extends RuntimeException {
        public AccountException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** HTTP 错误响应，携带状态码和 error code 以便 UI 区分不同错误。 */
    public static class AccountHttpException extends AccountException {
        private final int statusCode;
        private final String errorCode;
        public AccountHttpException(int statusCode, String errorCode, String message) {
            super(message, null);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }
        public int statusCode() { return statusCode; }
        public String errorCode() { return errorCode; }
    }
}
