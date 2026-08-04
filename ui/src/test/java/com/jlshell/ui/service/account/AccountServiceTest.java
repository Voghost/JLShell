package com.jlshell.ui.service.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.SecureSettingsService;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    private ExecutorService executor;
    private HttpServer server;
    private ScheduledThreadPoolExecutor scheduler;
    private AccountService service;
    private InMemorySettings settings;
    private InMemorySecureSettings secureSettings;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        scheduler = new ScheduledThreadPoolExecutor(1);
        settings = new InMemorySettings();
        secureSettings = new InMemorySecureSettings();
        settings.set("account.authToken", "saved-token");
        settings.set("account.accountId", "account-1");
        settings.set("account.expiresAt", "2099-01-01T00:00:00Z");
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        } else if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void restoredSessionImmediatelyReportsCurrentSshCount() throws Exception {
        CountDownLatch reportReceived = new CountDownLatch(1);
        AtomicReference<String> reportBody = new AtomicReference<>();
        startServer(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/api/v1/account/me" -> respond(exchange, 200, meResponse(0));
                case "/api/v1/account/report-stats" -> {
                    reportBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, meResponse(3));
                    reportReceived.countDown();
                }
                default -> respond(exchange, 404, "{}");
            }
        });
        service = service(Duration.ofHours(1), Duration.ofHours(1));
        service.setLiveConnectionCount(3);

        AccountService.AccountSession session = service.validateSession().join();

        assertEquals("account-1", session.id());
        assertTrue(reportReceived.await(2, TimeUnit.SECONDS));
        assertTrue(reportBody.get().contains("\"connectionCount\":3"));
        assertTrue(reportBody.get().matches(".*\"deviceId\":\"[^\"]+\".*"));
        assertFalse(settings.get("device.id", "").isBlank());
        assertTrue(settings.get("account.authToken").isEmpty());
        assertEquals("saved-token", secureSettings.get("account.authToken").orElseThrow());
    }

    @Test
    void scheduledHeartbeatRunsRegardlessOfExpiryAndRetriesAfterFailure() throws Exception {
        AtomicInteger heartbeatCalls = new AtomicInteger();
        CountDownLatch twoHeartbeats = new CountDownLatch(2);
        startServer(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/api/v1/account/me" -> respond(exchange, 200, meResponse(0));
                case "/api/v1/account/report-stats" -> respond(exchange, 200, meResponse(0));
                case "/api/v1/account/heartbeat" -> {
                    int call = heartbeatCalls.incrementAndGet();
                    twoHeartbeats.countDown();
                    if (call == 1) {
                        respond(exchange, 500, "{\"message\":\"temporary failure\"}");
                    } else {
                        respond(exchange, 200, authResponse("refreshed-token"));
                    }
                }
                default -> respond(exchange, 404, "{}");
            }
        });
        service = service(Duration.ofMillis(40), Duration.ofHours(1));

        service.validateSession().join();

        assertTrue(twoHeartbeats.await(3, TimeUnit.SECONDS));
        awaitToken("refreshed-token");
        assertTrue(heartbeatCalls.get() >= 2);
        assertEquals("refreshed-token", secureSettings.get("account.authToken").orElseThrow());
    }

    @Test
    void browserLoginUsesLoopbackPkceAndStoresDesktopToken() throws Exception {
        settings.remove("account.authToken");
        AtomicReference<String> exchangeBody = new AtomicReference<>();
        startServer(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/api/v1/desktop-token" -> {
                    exchangeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, authResponse("browser-token"));
                }
                case "/api/v1/account/report-stats" -> respond(exchange, 200, meResponse(0));
                default -> respond(exchange, 404, "{}");
            }
        });
        service = service(Duration.ofHours(1), Duration.ofHours(1));

        AccountService.BrowserLoginAttempt attempt = service.loginWithBrowser();
        Map<String, String> authorization = query(attempt.authorizationUri());
        URI callback = URI.create(authorization.get("redirect_uri") + "?code=one-time-code&state="
                + authorization.get("state"));
        HttpResponse<String> callbackResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(callback).GET().build(), HttpResponse.BodyHandlers.ofString());
        AccountService.AccountSession session = attempt.completion().get(2, TimeUnit.SECONDS);

        assertEquals(200, callbackResponse.statusCode());
        assertEquals("browser-token", session.token());
        assertEquals("browser-token", secureSettings.get("account.authToken").orElseThrow());
        var exchanged = JsonParser.parseString(exchangeBody.get()).getAsJsonObject();
        assertEquals("one-time-code", exchanged.get("code").getAsString());
        assertEquals(authorization.get("redirect_uri"), exchanged.get("redirectUri").getAsString());
        assertEquals(authorization.get("code_challenge"),
                pkceChallenge(exchanged.get("codeVerifier").getAsString()));
    }

    @Test
    void browserLoginRejectsMismatchedStateBeforeTokenExchange() throws Exception {
        settings.remove("account.authToken");
        AtomicReference<String> exchangeBody = new AtomicReference<>();
        startServer(exchange -> {
            if ("/api/v1/desktop-token".equals(exchange.getRequestURI().getPath())) {
                exchangeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, authResponse("must-not-be-issued"));
            } else {
                respond(exchange, 404, "{}");
            }
        });
        service = service(Duration.ofHours(1), Duration.ofHours(1));

        AccountService.BrowserLoginAttempt attempt = service.loginWithBrowser();
        Map<String, String> authorization = query(attempt.authorizationUri());
        URI callback = URI.create(authorization.get("redirect_uri")
                + "?code=attacker-code&state=wrong-state");
        HttpResponse<String> callbackResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(callback).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, callbackResponse.statusCode());
        assertThrows(ExecutionException.class, () -> attempt.completion().get(2, TimeUnit.SECONDS));
        assertNull(exchangeBody.get());
        assertTrue(secureSettings.get("account.authToken").isEmpty());
    }

    @Test
    void browserLoginRejectsNonTlsRemoteWebsite() {
        DesktopBrowserLogin login = new DesktopBrowserLogin(
                executor, uri -> false, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class,
                () -> login.start(URI.create("http://example.test"), "device-123", "test"));
    }

    private AccountService service(Duration heartbeatInterval, Duration reportInterval) {
        settings.set(AccountService.SETTINGS_BASE_URL, baseUrl());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(executor)
                .build();
        return new AccountService(settings, secureSettings, executor, client, scheduler,
                heartbeatInterval, reportInterval,
                new DesktopBrowserLogin(executor, uri -> false, Duration.ofMinutes(3)));
    }

    private void awaitToken(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!expected.equals(secureSettings.get("account.authToken").orElse(""))
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private void startServer(ExchangeHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable error) {
                respond(exchange, 500, "{}");
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String meResponse(int connectionCount) {
        return """
                {
                  "id": "account-1",
                  "username": "tester",
                  "email": "tester@example.test",
                  "role": "USER",
                  "passwordChangeRequired": false,
                  "connectionCount": %d,
                  "terminalCount": 1,
                  "historicalDeviceCount": 2
                }
                """.formatted(connectionCount);
    }

    private static String authResponse(String token) {
        return """
                {
                  "token": "%s",
                  "expiresAt": "2099-01-01T00:00:00Z",
                  "account": %s
                }
                """.formatted(token, meResponse(0));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            result.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String pkceChallenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static class InMemorySettings implements AppSettingsService {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }

    private static final class InMemorySecureSettings implements SecureSettingsService {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
