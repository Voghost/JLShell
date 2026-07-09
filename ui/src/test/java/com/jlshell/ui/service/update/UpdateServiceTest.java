package com.jlshell.ui.service.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlshell.core.service.AppSettingsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateServiceTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private HttpServer server;
    private InMemorySettings settings;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        settings = new InMemorySettings();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        System.clearProperty("jlshell.launcher.version");
    }

    @Test
    void checkLatestUsesClientUpdateApiContract() throws Exception {
        CapturedRequest captured = new CapturedRequest();
        startServer(exchange -> {
            captured.query = exchange.getRequestURI().getRawQuery();
            respond(exchange, 200, """
                    {
                      "updateAvailable": true,
                      "latestVersion": "0.1.42",
                      "channel": "stable",
                      "updateType": "jar",
                      "requiresFullInstaller": false,
                      "minLauncherVersion": "0.1.0",
                      "releaseNotesUrl": "https://jlshell.oomn.net/releases/0.1.42",
                      "asset": {
                        "fileName": "jlshell-app-0.1.42.jar",
                        "url": "https://example.test/jlshell-app-0.1.42.jar",
                        "size": 12,
                        "sha256": "abc"
                      }
                    }
                    """);
        });
        settings.set(UpdateService.SETTINGS_BASE_URL, baseUrl());

        UpdateService.UpdateResponse response = service().checkLatest("0.1.41").join();

        assertTrue(response.updateAvailable());
        assertEquals("0.1.42", response.latestVersion());
        assertTrue(captured.query.contains("channel=stable"));
        assertTrue(captured.query.contains("current=0.1.41"));
        assertTrue(captured.query.contains("os="));
        assertTrue(captured.query.contains("arch="));
        assertFalse(captured.query.contains("package=jar"));
    }

    @Test
    void legacyPlaceholderBaseUrlFallsBackToPackagedDefault() {
        settings.set(UpdateService.SETTINGS_BASE_URL, "https://jlshell.com");

        assertEquals(UpdateService.DEFAULT_BASE_URL, service().configuredBaseUrl());
    }

    @Test
    void updateApiNotFoundMapsToFriendlyUserMessageKey() throws Exception {
        startServer(exchange -> respond(exchange, 404, "not found"));
        settings.set(UpdateService.SETTINGS_BASE_URL, baseUrl());

        CompletionException error = assertThrows(CompletionException.class,
                () -> service().checkLatest("0.1.41").join());

        assertEquals("updates.error.serviceUnavailable", UpdateService.userMessageKey(error));
        assertFalse(UpdateService.userMessageKey(error).contains("404"));
    }

    @Test
    void downloadsVerifiedJarAndWritesPendingActivation() throws Exception {
        byte[] jarBytes = "app-0.1.42".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> respond(exchange, 200, jarBytes));
        System.setProperty("jlshell.launcher.version", "0.1.0");
        UpdateService.UpdateResponse response = updateResponse(
                new UpdateService.UpdateAsset("jlshell-app-0.1.42.jar", baseUrl() + "/jar", jarBytes.length, sha256(jarBytes)),
                null);

        UpdateService.DownloadResult result = service().downloadAndStage(response).join();

        assertTrue(result.restartRequired());
        assertEquals(tempDir.resolve("versions/0.1.42/jlshell-app-0.1.42.jar"), result.file());
        assertEquals("app-0.1.42", Files.readString(result.file(), StandardCharsets.UTF_8));
        String pending = Files.readString(tempDir.resolve("pending.json"), StandardCharsets.UTF_8);
        assertTrue(pending.contains("\"version\": \"0.1.42\""));
        assertTrue(pending.contains("\"startupConfirmed\": false"));
    }

    @Test
    void keepsOnlyLatestTwoStagedJarVersions() throws Exception {
        Files.createDirectories(tempDir.resolve("versions/0.1.39"));
        Files.createDirectories(tempDir.resolve("versions/0.1.40"));
        Files.createDirectories(tempDir.resolve("versions/0.1.41"));
        Files.writeString(tempDir.resolve("versions/0.1.39/old.jar"), "old-39", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("versions/0.1.40/old.jar"), "old-40", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("versions/0.1.41/old.jar"), "old-41", StandardCharsets.UTF_8);
        byte[] jarBytes = "app-0.1.42".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> respond(exchange, 200, jarBytes));
        System.setProperty("jlshell.launcher.version", "0.1.0");
        UpdateService.UpdateResponse response = updateResponse(
                new UpdateService.UpdateAsset("jlshell-app-0.1.42.jar", baseUrl() + "/jar", jarBytes.length, sha256(jarBytes)),
                null);

        UpdateService.DownloadResult result = service().downloadAndStage(response).join();

        assertEquals(tempDir.resolve("versions/0.1.42/jlshell-app-0.1.42.jar"), result.file());
        assertFalse(Files.exists(tempDir.resolve("versions/0.1.39")));
        assertFalse(Files.exists(tempDir.resolve("versions/0.1.40")));
        assertTrue(Files.exists(tempDir.resolve("versions/0.1.41")));
        assertTrue(Files.exists(tempDir.resolve("versions/0.1.42")));
    }

    @Test
    void fallsBackToInstallerWhenJarVerificationFails() throws Exception {
        byte[] installerBytes = "installer-0.1.42".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/jar")) {
                respond(exchange, 200, "corrupt".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, installerBytes);
            }
        });
        System.setProperty("jlshell.launcher.version", "0.1.0");
        UpdateService.UpdateResponse response = updateResponse(
                new UpdateService.UpdateAsset("jlshell-app-0.1.42.jar", baseUrl() + "/jar", 7, "bad-sha"),
                new UpdateService.UpdateAsset("JLShell-0.1.42-win-x64.msi", baseUrl() + "/installer",
                        installerBytes.length, sha256(installerBytes)));

        UpdateService.DownloadResult result = service().downloadAndStage(response).join();

        assertFalse(result.restartRequired());
        assertEquals(tempDir.resolve("downloads/JLShell-0.1.42-win-x64.msi"), result.file());
        assertEquals("installer-0.1.42", Files.readString(result.file(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempDir.resolve("pending.json")));
    }

    private UpdateService service() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new UpdateService(settings, executor, client, tempDir);
    }

    private UpdateService.UpdateResponse updateResponse(UpdateService.UpdateAsset asset,
                                                        UpdateService.UpdateAsset fallbackInstaller) {
        return new UpdateService.UpdateResponse(
                true,
                "0.1.42",
                "stable",
                "jar",
                false,
                "0.1.0",
                baseUrl() + "/releases/0.1.42",
                asset,
                fallbackInstaller);
    }

    private void startServer(ExchangeHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable error) {
                respond(exchange, 500, error.getMessage() == null ? "error" : error.getMessage());
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static class CapturedRequest {
        private String query;
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
}
