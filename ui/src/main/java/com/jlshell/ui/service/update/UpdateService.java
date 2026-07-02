package com.jlshell.ui.service.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jlshell.core.service.AppSettingsService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class UpdateService {

    public static final String SETTINGS_BASE_URL = "updates.baseUrl";
    public static final String SETTINGS_CHANNEL = "updates.channel";
    public static final String SETTINGS_AUTO_CHECK = "updates.autoCheck.enabled";
    public static final String SETTINGS_IGNORED_VERSION = "updates.ignoredVersion";
    public static final String SETTINGS_LAST_CHECK_AT = "updates.lastCheckAt";

    private static final String DEFAULT_BASE_URL = "https://jlshell.com";
    private static final String DEFAULT_CHANNEL = "stable";

    private final AppSettingsService appSettings;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public UpdateService(AppSettingsService appSettings, ExecutorService executor) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.executor = Objects.requireNonNull(executor);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<UpdateResponse> checkLatest(String currentVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = latestUri(currentVersion);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Update API returned HTTP " + response.statusCode());
                }
                appSettings.set(SETTINGS_LAST_CHECK_AT, Instant.now().toString());
                return gson.fromJson(response.body(), UpdateResponse.class);
            } catch (Exception e) {
                throw new UpdateException("Failed to check for updates", e);
            }
        }, executor);
    }

    public CompletableFuture<DownloadResult> downloadAndStage(UpdateResponse update) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean jarUpdate = canUseJarUpdate(update);
                UpdateAsset asset = jarUpdate ? update.asset() : update.fallbackInstaller();
                if (asset == null || blank(asset.url()) || blank(asset.fileName())) {
                    throw new IOException("No downloadable update asset returned by API");
                }

                Path downloads = updatesDir().resolve("downloads");
                Files.createDirectories(downloads);
                Path tempFile = downloads.resolve(asset.fileName() + ".part");
                Path finalFile = downloads.resolve(asset.fileName());
                download(asset.url(), tempFile);
                verifySha256(tempFile, asset.sha256());
                Files.move(tempFile, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                if (jarUpdate) {
                    Path versionDir = updatesDir().resolve("versions").resolve(update.latestVersion());
                    Files.createDirectories(versionDir);
                    Path stagedJar = versionDir.resolve(asset.fileName());
                    Files.move(finalFile, stagedJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    writePending(update.latestVersion(), stagedJar, asset.sha256());
                    return new DownloadResult(true, true, stagedJar);
                }

                return new DownloadResult(true, false, finalFile);
            } catch (Exception e) {
                throw new UpdateException("Failed to download update", e);
            }
        }, executor);
    }

    public String configuredChannel() {
        return appSettings.get(SETTINGS_CHANNEL, DEFAULT_CHANNEL);
    }

    public boolean autoCheckEnabled() {
        return Boolean.parseBoolean(appSettings.get(SETTINGS_AUTO_CHECK, "true"));
    }

    public boolean isIgnored(UpdateResponse response) {
        return response != null
                && !blank(response.latestVersion())
                && response.latestVersion().equals(appSettings.get(SETTINGS_IGNORED_VERSION, ""));
    }

    public void ignoreVersion(String version) {
        if (!blank(version)) {
            appSettings.set(SETTINGS_IGNORED_VERSION, version);
        }
    }

    private URI latestUri(String currentVersion) {
        String baseUrl = appSettings.get(SETTINGS_BASE_URL, DEFAULT_BASE_URL).strip();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String query = "channel=" + enc(configuredChannel())
                + "&current=" + enc(cleanVersion(currentVersion))
                + "&os=" + enc(osName())
                + "&arch=" + enc(archName())
                + "&package=jar";
        return URI.create(baseUrl + "/api/v1/updates/latest?" + query);
    }

    private void download(String url, Path destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(destination);
            throw new IOException("Download returned HTTP " + response.statusCode());
        }
    }

    private void verifySha256(Path file, String expected) throws Exception {
        if (blank(expected)) {
            throw new IOException("Missing sha256 for update asset");
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(file);
            throw new IOException("Update checksum mismatch");
        }
    }

    private void writePending(String version, Path jarPath, String sha256) throws IOException {
        Files.createDirectories(updatesDir());
        PendingUpdate pending = new PendingUpdate(version, jarPath.toAbsolutePath().toString(), sha256, false);
        Files.writeString(updatesDir().resolve("pending.json"), gson.toJson(pending), StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path updatesDir() {
        return Path.of(System.getProperty("user.home"), ".jlshell", "updates");
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        if (os.contains("linux")) return "linux";
        return os.replaceAll("[^a-z0-9]+", "-");
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return arch;
    }

    private static String cleanVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        return version.replace(".RELEASE", "").replace("-SNAPSHOT", "");
    }

    private static boolean canUseJarUpdate(UpdateResponse update) {
        return update != null
                && "jar".equalsIgnoreCase(update.updateType())
                && !update.requiresFullInstaller()
                && compareVersions(System.getProperty("jlshell.launcher.version", "0.0.0"),
                        update.minLauncherVersion()) >= 0;
    }

    private static int compareVersions(String left, String right) {
        String l = cleanVersion(left);
        String r = cleanVersion(right == null || right.isBlank() ? "0.0.0" : right);
        String[] lp = l.split("[.-]");
        String[] rp = r.split("[.-]");
        int max = Math.max(lp.length, rp.length);
        for (int i = 0; i < max; i++) {
            int li = numberPart(lp, i);
            int ri = numberPart(rp, i);
            if (li != ri) return Integer.compare(li, ri);
        }
        return 0;
    }

    private static int numberPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record UpdateResponse(
            boolean updateAvailable,
            String latestVersion,
            String channel,
            String updateType,
            boolean requiresFullInstaller,
            String minLauncherVersion,
            String releaseNotesUrl,
            UpdateAsset asset,
            UpdateAsset fallbackInstaller
    ) {}

    public record UpdateAsset(
            String fileName,
            String url,
            long size,
            String sha256
    ) {}

    public record DownloadResult(boolean success, boolean restartRequired, Path file) {}

    private record PendingUpdate(String version, String jarPath, String sha256, boolean startupConfirmed) {}

    public static class UpdateException extends RuntimeException {
        public UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
