package com.jlshell.ui.service.account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/** 桌面端回环 Authorization Code + PKCE S256 登录。 */
final class DesktopBrowserLogin {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_QUERY_LENGTH = 4096;

    private final Executor executor;
    private final BrowserLauncher browserLauncher;
    private final Duration timeout;

    DesktopBrowserLogin(Executor executor) {
        this(executor, DesktopBrowserLogin::browse, Duration.ofMinutes(3));
    }

    DesktopBrowserLogin(Executor executor, BrowserLauncher browserLauncher, Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    Attempt start(URI baseUri, String deviceId, String deviceName) throws IOException {
        requireSecureBaseUri(baseUri);
        String verifier = random(64);
        String challenge = sha256Url(verifier);
        String expectedState = random(32);
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0), 1);
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        CompletableFuture<Exchange> exchange = new CompletableFuture<>();
        server.createContext("/callback", request -> handleCallback(request, expectedState,
                verifier, redirectUri, exchange));
        server.setExecutor(executor);
        server.start();

        URI authorizationUri = URI.create(trimTrailingSlash(baseUri.toString())
                + "/desktop/authorize?code_challenge=" + encode(challenge)
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(expectedState)
                + "&device_id=" + encode(deviceId)
                + "&device_name=" + encode(deviceName));
        Attempt attempt = new Attempt(authorizationUri, exchange, server, browserLauncher);
        CompletableFuture.delayedExecutor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> exchange.completeExceptionally(
                        new TimeoutException("Desktop browser login timed out")));
        exchange.whenComplete((result, error) -> server.stop(0));
        attempt.openBrowser();
        return attempt;
    }

    private static void handleCallback(HttpExchange request, String expectedState,
                                       String verifier, String redirectUri,
                                       CompletableFuture<Exchange> result) throws IOException {
        int status = 200;
        String message;
        Exchange completedExchange = null;
        RuntimeException failure = null;
        try {
            if (!"GET".equalsIgnoreCase(request.getRequestMethod())) {
                throw new IllegalArgumentException("Unsupported callback method");
            }
            String rawQuery = request.getRequestURI().getRawQuery();
            if (rawQuery == null || rawQuery.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("Invalid callback query");
            }
            Map<String, String> query = parseQuery(rawQuery);
            if (query.containsKey("error")) {
                throw new CancellationException("Desktop authorization was rejected");
            }
            String state = query.get("state");
            String code = query.get("code");
            if (state == null || code == null || code.isBlank()
                    || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.US_ASCII),
                    state.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Invalid desktop authorization callback");
            }
            completedExchange = new Exchange(code, verifier, redirectUri);
            message = "JLShell 登录授权已完成，可以关闭此页面。";
        } catch (CancellationException error) {
            status = 400;
            failure = error;
            message = "JLShell 登录授权已取消，可以关闭此页面。";
        } catch (RuntimeException error) {
            status = 400;
            failure = error;
            message = "JLShell 登录回调无效，请返回客户端重试。";
        }
        byte[] body = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>JLShell</title><body><p>" + message + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
        request.close();
        if (completedExchange != null) {
            result.complete(completedExchange);
        } else {
            result.completeExceptionally(failure == null
                    ? new IllegalStateException("Desktop callback failed") : failure);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate callback parameter");
            }
        }
        return values;
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256Url(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static boolean browse(URI uri) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void requireSecureBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                || "::1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        if (!("https".equalsIgnoreCase(uri.getScheme()) || loopbackHttp)
                || uri.getUserInfo() != null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Account Website URL must use HTTPS (loopback HTTP is allowed for development)");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    record Exchange(String code, String verifier, String redirectUri) { }

    static final class Attempt {
        private final URI authorizationUri;
        private final CompletableFuture<Exchange> completion;
        private final HttpServer server;
        private final BrowserLauncher browserLauncher;
        private volatile boolean browserOpened;

        private Attempt(URI authorizationUri, CompletableFuture<Exchange> completion,
                        HttpServer server, BrowserLauncher browserLauncher) {
            this.authorizationUri = authorizationUri;
            this.completion = completion;
            this.server = server;
            this.browserLauncher = browserLauncher;
        }

        URI authorizationUri() {
            return authorizationUri;
        }

        CompletableFuture<Exchange> completion() {
            return completion;
        }

        boolean browserOpened() {
            return browserOpened;
        }

        boolean openBrowser() {
            browserOpened = browserLauncher.open(authorizationUri) || browserOpened;
            return browserOpened;
        }

        void cancel() {
            completion.completeExceptionally(new CancellationException("Desktop browser login cancelled"));
            server.stop(0);
        }
    }

    @FunctionalInterface
    interface BrowserLauncher {
        boolean open(URI uri);
    }
}
