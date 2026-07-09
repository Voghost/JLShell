package com.jlshell.ui.service.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.ui.config.JlshellDefaults;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    public static final String SETTINGS_BASE_URL = "updates.baseUrl";
    public static final String SETTINGS_CHANNEL = "updates.channel";
    public static final String SETTINGS_AUTO_CHECK = "updates.autoCheck.enabled";
    public static final String SETTINGS_IGNORED_VERSION = "updates.ignoredVersion";
    public static final String SETTINGS_LAST_CHECK_AT = "updates.lastCheckAt";

    public static final String DEFAULT_BASE_URL = JlshellDefaults.updateBaseUrl();
    private static final String LEGACY_PLACEHOLDER_BASE_URL = "https://jlshell.com";
    private static final String DEFAULT_CHANNEL = "stable";
    private static final int MAX_STAGED_JAR_VERSIONS = 2;

    private final AppSettingsService appSettings;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final Path updatesDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public UpdateService(AppSettingsService appSettings, ExecutorService executor) {
        this(appSettings, executor, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), defaultUpdatesDir());
    }

    UpdateService(AppSettingsService appSettings, ExecutorService executor, HttpClient httpClient, Path updatesDir) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.executor = Objects.requireNonNull(executor);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.updatesDir = Objects.requireNonNull(updatesDir);
    }

    public CompletableFuture<UpdateResponse> checkLatest(String currentVersion) {
        return CompletableFuture.supplyAsync(() -> {
            URI uri = null;
            try {
                uri = latestUri(currentVersion);
                log.info("Checking JLShell updates: uri={}", uri);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .header("User-Agent", "JLShell-Updater/" + cleanVersion(currentVersion))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("JLShell update API request failed: status={}, uri={}, response={}",
                            response.statusCode(), uri, abbreviate(response.body()));
                    throw new UpdateException(
                            "Update API request failed",
                            new UpdateHttpException(response.statusCode(), uri, response.body()),
                            httpUserMessageKey(response.statusCode(), false)
                    );
                }
                appSettings.set(SETTINGS_LAST_CHECK_AT, Instant.now().toString());
                UpdateResponse updateResponse = gson.fromJson(response.body(), UpdateResponse.class);
                log.info("JLShell update API response received: available={}, latestVersion={}, channel={}, type={}",
                        updateResponse != null && updateResponse.updateAvailable(),
                        updateResponse == null ? "" : updateResponse.latestVersion(),
                        updateResponse == null ? "" : updateResponse.channel(),
                        updateResponse == null ? "" : updateResponse.updateType());
                return updateResponse;
            } catch (UpdateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("JLShell update check failed: uri={}", uri, e);
                throw new UpdateException("Failed to check for updates", e, "updates.error.network");
            }
        }, executor);
    }

    public CompletableFuture<DownloadResult> downloadAndStage(UpdateResponse update) {
        return downloadAndStage(update, null);
    }

    /** 下载更新。progressCallback 在后台线程调用，值为 0.0~1.0。 */
    public CompletableFuture<DownloadResult> downloadAndStage(UpdateResponse update, DoubleConsumer progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (update == null || !update.updateAvailable()) {
                    throw new IOException("No update is available");
                }
                if (canUseJarUpdate(update) && downloadable(update.asset())) {
                    try {
                        return downloadAndStageJar(update, update.asset(), progressCallback);
                    } catch (Exception jarError) {
                        if (!downloadable(update.fallbackInstaller())) {
                            throw jarError;
                        }
                        log.warn("JLShell jar update failed; falling back to full installer: version={}, asset={}",
                                update.latestVersion(), update.asset().fileName(), jarError);
                    }
                }
                return downloadInstaller(update.fallbackInstaller(), progressCallback);
            } catch (UpdateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("JLShell update download flow failed: version={}, type={}",
                        update == null ? "" : update.latestVersion(),
                        update == null ? "" : update.updateType(),
                        e);
                throw new UpdateException("Failed to download update", e, classifyUserMessageKey(e));
            }
        }, executor);
    }

    public String configuredChannel() {
        return appSettings.get(SETTINGS_CHANNEL, DEFAULT_CHANNEL);
    }

    public String configuredBaseUrl() {
        return configuredBaseUrl(appSettings);
    }

    public static String configuredBaseUrl(AppSettingsService appSettings) {
        return normalizeBaseUrl(appSettings.get(SETTINGS_BASE_URL, ""), DEFAULT_BASE_URL);
    }

    public static String normalizeBaseUrl(String configured, String defaultValue) {
        if (blank(configured)) {
            return defaultValue;
        }
        String value = configured.strip();
        if (LEGACY_PLACEHOLDER_BASE_URL.equalsIgnoreCase(trimTrailingSlash(value))) {
            return defaultValue;
        }
        return value;
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
        String baseUrl = configuredBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String query = "channel=" + enc(configuredChannel())
                + "&current=" + enc(cleanVersion(currentVersion))
                + "&os=" + enc(osName())
                + "&arch=" + enc(archName());
        return URI.create(baseUrl + "/api/v1/updates/latest?" + query);
    }

    private DownloadResult downloadAndStageJar(UpdateResponse update, UpdateAsset asset, DoubleConsumer progressCallback) throws Exception {
        log.info("Downloading JLShell jar update: version={}, fileName={}, url={}, size={}",
                update.latestVersion(), asset.fileName(), asset.url(), asset.size());
        Path downloaded = downloadVerified(asset, progressCallback);
        Path versionDir = updatesDir.resolve("versions").resolve(update.latestVersion());
        Files.createDirectories(versionDir);
        Path stagedJar = versionDir.resolve(asset.fileName());
        Files.move(downloaded, stagedJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writePending(update.latestVersion(), stagedJar, asset.sha256());
        pruneStagedJarVersions();
        log.info("Staged JLShell jar update: version={}, path={}", update.latestVersion(), stagedJar);
        return new DownloadResult(true, true, stagedJar);
    }

    private DownloadResult downloadInstaller(UpdateAsset asset, DoubleConsumer progressCallback) throws Exception {
        if (!downloadable(asset)) {
            log.warn("JLShell update API did not provide a downloadable asset");
            throw new UpdateException(
                    "No downloadable update asset returned by API",
                    null,
                    "updates.error.noPackage"
            );
        }
        log.info("Downloading JLShell full installer: fileName={}, url={}, size={}",
                asset.fileName(), asset.url(), asset.size());
        Path file = downloadVerified(asset, progressCallback);
        log.info("Downloaded JLShell full installer: path={}", file);
        return new DownloadResult(true, false, file);
    }

    private Path downloadVerified(UpdateAsset asset, DoubleConsumer progressCallback) throws Exception {
        Path downloads = updatesDir.resolve("downloads");
        Files.createDirectories(downloads);
        Path tempFile = downloads.resolve(asset.fileName() + ".part");
        Path finalFile = downloads.resolve(asset.fileName());
        Files.deleteIfExists(tempFile);
        download(asset.url(), tempFile, asset.size(), progressCallback);
        verifySize(tempFile, asset.size());
        verifySha256(tempFile, asset.sha256());
        Files.move(tempFile, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return finalFile;
    }

    private void download(String url, Path destination, long knownSize, DoubleConsumer progressCallback) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "JLShell-Updater")
                .GET()
                .build();
        // 使用 InputStream 模式以支持进度回调
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(destination);
            log.warn("JLShell update asset download failed: status={}, url={}", response.statusCode(), url);
            throw new UpdateException(
                    "Update asset download failed",
                    new UpdateHttpException(response.statusCode(), URI.create(url), ""),
                    httpUserMessageKey(response.statusCode(), true)
            );
        }
        // 从 Content-Length 或已知大小获取总大小
        long totalSize = knownSize;
        if (totalSize <= 0) {
            String contentLength = response.headers().firstValue("Content-Length").orElse("");
            if (!contentLength.isBlank()) {
                try { totalSize = Long.parseLong(contentLength); } catch (NumberFormatException ignored) {}
            }
        }
        long total = totalSize;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int read;
            long lastReport = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (progressCallback != null && total > 0) {
                    long now = System.currentTimeMillis();
                    // 限制回调频率：最多每 100ms 一次
                    if (now - lastReport >= 100 || downloaded >= total) {
                        lastReport = now;
                        progressCallback.accept(Math.min(1.0, (double) downloaded / total));
                    }
                }
            }
        }
        if (progressCallback != null) {
            progressCallback.accept(1.0);
        }
    }

    private void verifySize(Path file, long expectedSize) throws IOException {
        if (expectedSize > 0) {
            long actual = Files.size(file);
            if (actual != expectedSize) {
                Files.deleteIfExists(file);
                log.warn("JLShell update asset size mismatch: file={}, expected={}, actual={}", file, expectedSize, actual);
                throw new UpdateException(
                        "Update size mismatch",
                        new IOException("Update size mismatch"),
                        "updates.error.verifyFailed"
                );
            }
        }
    }

    private void verifySha256(Path file, String expected) throws Exception {
        if (blank(expected)) {
            log.warn("JLShell update asset is missing sha256: file={}", file);
            throw new UpdateException(
                    "Missing sha256 for update asset",
                    new IOException("Missing sha256 for update asset"),
                    "updates.error.verifyFailed"
            );
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(file);
            log.warn("JLShell update asset checksum mismatch: file={}, expected={}, actual={}", file, expected, actual);
            throw new UpdateException(
                    "Update checksum mismatch",
                    new IOException("Update checksum mismatch"),
                    "updates.error.verifyFailed"
            );
        }
    }

    private void writePending(String version, Path jarPath, String sha256) throws IOException {
        Files.createDirectories(updatesDir);
        PendingUpdate pending = new PendingUpdate(version, jarPath.toAbsolutePath().toString(), sha256, false);
        Files.writeString(updatesDir.resolve("pending.json"), gson.toJson(pending), StandardCharsets.UTF_8);
    }

    private void pruneStagedJarVersions() {
        Path versionsRoot = updatesDir.resolve("versions");
        if (!Files.isDirectory(versionsRoot)) {
            return;
        }
        try {
            Set<String> keep = preferredRetainedVersions();
            List<Path> versionDirs = new ArrayList<>();
            try (Stream<Path> stream = Files.list(versionsRoot)) {
                stream.filter(Files::isDirectory).forEach(versionDirs::add);
            }
            versionDirs.sort((left, right) -> compareVersions(
                    right.getFileName().toString(),
                    left.getFileName().toString()));
            for (Path dir : versionDirs) {
                if (keep.size() >= MAX_STAGED_JAR_VERSIONS) {
                    break;
                }
                keep.add(dir.getFileName().toString());
            }
            for (Path dir : versionDirs) {
                String version = dir.getFileName().toString();
                if (!keep.contains(version)) {
                    deleteRecursively(dir);
                    log.info("Pruned old JLShell jar update: version={}, path={}", version, dir);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to prune old JLShell jar updates: dir={}", versionsRoot, e);
        }
    }

    private Set<String> preferredRetainedVersions() {
        Set<String> versions = new LinkedHashSet<>();
        addEntryVersion(versions, updatesDir.resolve("pending.json"));
        addEntryVersion(versions, updatesDir.resolve("current.json"));
        addEntryVersion(versions, updatesDir.resolve("previous.json"));
        if (versions.size() <= MAX_STAGED_JAR_VERSIONS) {
            return versions;
        }
        Set<String> limited = new LinkedHashSet<>();
        for (String version : versions) {
            limited.add(version);
            if (limited.size() >= MAX_STAGED_JAR_VERSIONS) {
                break;
            }
        }
        return limited;
    }

    private void addEntryVersion(Set<String> versions, Path entryFile) {
        if (versions.size() >= MAX_STAGED_JAR_VERSIONS || !Files.isRegularFile(entryFile)) {
            return;
        }
        try {
            PendingUpdate entry = gson.fromJson(Files.readString(entryFile, StandardCharsets.UTF_8), PendingUpdate.class);
            if (entry != null && !blank(entry.version())) {
                versions.add(entry.version());
            }
        } catch (Exception ignored) {
            // Invalid update metadata should not block cleanup.
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
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

    private static Path defaultUpdatesDir() {
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

    private static boolean downloadable(UpdateAsset asset) {
        return asset != null && !blank(asset.url()) && !blank(asset.fileName());
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

    public static String userMessageKey(Throwable error) {
        Throwable current = unwrap(error);
        while (current != null) {
            if (current instanceof UpdateException updateException && !blank(updateException.userMessageKey())) {
                return updateException.userMessageKey();
            }
            current = current.getCause();
        }
        return "updates.error.generic";
    }

    private static String classifyUserMessageKey(Throwable error) {
        Throwable current = unwrap(error);
        while (current != null) {
            if (current instanceof UpdateHttpException httpException) {
                return httpUserMessageKey(httpException.statusCode(), true);
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("checksum")) {
                return "updates.error.verifyFailed";
            }
            current = current.getCause();
        }
        return "updates.error.network";
    }

    private static String httpUserMessageKey(int statusCode, boolean download) {
        if (statusCode == 404) {
            return download ? "updates.error.assetUnavailable" : "updates.error.serviceUnavailable";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "updates.error.serviceUnavailable";
        }
        if (statusCode >= 500) {
            return "updates.error.serviceUnavailable";
        }
        return "updates.error.network";
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").strip();
        return compact.length() <= 512 ? compact : compact.substring(0, 512) + "...";
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
        private final String userMessageKey;

        public UpdateException(String message, Throwable cause) {
            this(message, cause, "updates.error.generic");
        }

        public UpdateException(String message, Throwable cause, String userMessageKey) {
            super(message, cause);
            this.userMessageKey = userMessageKey;
        }

        public String userMessageKey() {
            return userMessageKey;
        }
    }

    public static class UpdateHttpException extends IOException {
        private final int statusCode;
        private final URI uri;
        private final String responseBody;

        public UpdateHttpException(int statusCode, URI uri, String responseBody) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.uri = uri;
            this.responseBody = responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public URI uri() {
            return uri;
        }

        public String responseBody() {
            return responseBody;
        }
    }
}
