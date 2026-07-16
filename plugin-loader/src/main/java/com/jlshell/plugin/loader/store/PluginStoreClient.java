package com.jlshell.plugin.loader.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import com.jlshell.plugin.api.PluginScope;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * client-plugin-store-api 的 HTTP 客户端。所有公开 API 均无需认证；下载客户端
 * 显式启用重定向，因此不会保存 OSS 的临时签名地址。
 */
public final class PluginStoreClient {
    public static final String DEFAULT_BASE_URL = "https://jlshell.oomn.net";
    private static final Logger log = LoggerFactory.getLogger(PluginStoreClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_LOG_BODY_LENGTH = 600;
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final URI baseUri;
    private final HttpClient httpClient;
    private final Executor executor;
    private final Gson gson;

    public PluginStoreClient(String baseUrl, Executor executor) {
        this(normalizeBaseUri(baseUrl), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build(), executor);
    }

    PluginStoreClient(URI baseUri, HttpClient httpClient, Executor executor) {
        this.baseUri = Objects.requireNonNull(baseUri);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.executor = Objects.requireNonNull(executor);
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, context) ->
                        json == null || json.isJsonNull() ? null : Instant.parse(json.getAsString()))
                .create();
    }

    public CompletableFuture<PluginStorePage> search(PluginStoreSearch search) {
        Objects.requireNonNull(search);
        String hostVersion = normalizeHostVersion(search.hostVersion());
        StringJoiner query = new StringJoiner("&");
        addQueryParameter(query, "query", search.query());
        addQueryParameter(query, "scope", search.scope() == null ? null : search.scope().name());
        addQueryParameter(query, "hostVersion", hostVersion);
        addQueryParameter(query, "locale", localeTag(search.locale()));
        addQueryParameter(query, "page", String.valueOf(search.page()));
        addQueryParameter(query, "size", String.valueOf(search.size()));
        addQueryParameter(query, "sort", search.sort().name().toLowerCase(Locale.ROOT));
        log.info("Plugin store search triggered: query={}, scope={}, hostVersion={}, locale={}, page={}, size={}, sort={}",
                logValue(search.query()), search.scope(), hostVersion, localeTag(search.locale()),
                search.page(), search.size(), search.sort());
        return getJson("/api/v1/plugins?" + query, PluginStorePage.class, "search")
                .whenComplete((page, error) -> {
                    if (error != null) {
                        log.warn("Plugin store search failed: query={}, scope={}, hostVersion={}, cause={}",
                                logValue(search.query()), search.scope(), hostVersion, rootMessage(error));
                    } else {
                        log.info("Plugin store search completed: results={}, totalElements={}, page={}, totalPages={}",
                                page.content().size(), page.totalElements(), page.number(), page.totalPages());
                    }
                });
    }

    public CompletableFuture<PluginStoreDetail> detail(String pluginId, Locale locale, String hostVersion) {
        return getJson("/api/v1/plugins/" + path(pluginId) + "?locale=" + enc(localeTag(locale))
                + "&hostVersion=" + enc(normalizeHostVersion(hostVersion)), PluginStoreDetail.class, "detail");
    }

    public CompletableFuture<List<PluginStoreVersion>> versions(String pluginId) {
        URI uri = endpoint("/api/v1/plugins/" + path(pluginId) + "/versions");
        return CompletableFuture.supplyAsync(() -> {
            HttpResponse<String> response = send(uri, HttpResponse.BodyHandlers.ofString(), "versions");
            try {
                List<PluginStoreVersion> versions = gson.fromJson(response.body(),
                        TypeToken.getParameterized(List.class, PluginStoreVersion.class).getType());
                return versions == null ? List.of() : List.copyOf(versions);
            } catch (Exception e) {
                log.warn("Plugin store JSON parsing failed: operation=versions, uri={}, response={}",
                        uri, responseSummary(response.body()), e);
                throw new PluginStoreException("插件商店返回了无效 JSON", e, uri);
            }
        }, executor);
    }

    public CompletableFuture<PluginStoreUpdate> latestUpdate(String pluginId, String currentVersion, String hostVersion) {
        return getJson("/api/v1/plugins/" + path(pluginId) + "/updates/latest?current=" + enc(currentVersion)
                + "&hostVersion=" + enc(normalizeHostVersion(hostVersion)), PluginStoreUpdate.class, "latest-update");
    }

    public CompletableFuture<HttpResponse<InputStream>> download(String pluginId, String version) {
        URI uri = endpoint("/api/v1/plugins/" + path(pluginId) + "/versions/" + path(version) + "/download");
        return CompletableFuture.supplyAsync(
                () -> send(uri, HttpResponse.BodyHandlers.ofInputStream(), "download"), executor);
    }

    /** 确保服务端返回的候选确实比本地版本新，客户端绝不依赖字符串比较。 */
    public static boolean isNewer(String candidate, String current) {
        return SemVer.parse(candidate).compareTo(SemVer.parse(current)) > 0;
    }

    static String normalizeHostVersion(String value) {
        if (value == null || value.isBlank()) return value;
        String normalized = value.strip();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("(?i)(?:\\.RELEASE|\\.FINAL|\\.GA|-SNAPSHOT)$", "");
        SemVer.parse(normalized);
        return normalized;
    }

    private <T> CompletableFuture<T> getJson(String path, Class<T> type, String operation) {
        URI uri = endpoint(path);
        return CompletableFuture.supplyAsync(() -> {
            HttpResponse<String> response = send(uri, HttpResponse.BodyHandlers.ofString(), operation);
            try {
                T parsed = gson.fromJson(response.body(), type);
                if (parsed == null) {
                    throw new IllegalStateException("JSON response is null");
                }
                return parsed;
            } catch (Exception e) {
                log.warn("Plugin store JSON parsing failed: operation={}, uri={}, contentType={}, response={}",
                        operation, uri, response.headers().firstValue("Content-Type").orElse(""),
                        responseSummary(response.body()), e);
                throw new PluginStoreException("插件商店返回了无效 JSON", e, uri);
            }
        }, executor);
    }

    private <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> handler, String operation) {
        long requestId = REQUEST_SEQUENCE.incrementAndGet();
        long startedAt = System.nanoTime();
        int attempts = 0;
        log.info("Plugin store request started: requestId={}, operation={}, uri={}", requestId, operation, uri);
        while (true) {
            attempts++;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("User-Agent", "JLShell-PluginStore")
                        .GET()
                        .build();
                HttpResponse<T> response = httpClient.send(request, handler);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    log.info("Plugin store request completed: requestId={}, operation={}, status={}, attempts={}, durationMs={}, contentType={}, contentLength={}",
                            requestId, operation, status, attempts, elapsedMillis(startedAt),
                            response.headers().firstValue("Content-Type").orElse(""),
                            response.headers().firstValue("Content-Length").orElse(""));
                    return response;
                }
                if ((status == 429 || status >= 500) && attempts < MAX_ATTEMPTS) {
                    log.warn("Plugin store request will retry: requestId={}, operation={}, status={}, attempt={}, delayMs={}, uri={}, response={}",
                            requestId, operation, status, attempts, backoffMillis(attempts), uri,
                            responseSummary(response.body()));
                    backoff(attempts);
                    continue;
                }
                log.warn("Plugin store request failed: requestId={}, operation={}, status={}, attempts={}, durationMs={}, uri={}, response={}",
                        requestId, operation, status, attempts, elapsedMillis(startedAt), uri,
                        responseSummary(response.body()));
                throw new PluginStoreException("插件商店请求失败（HTTP " + status + "）", status, uri);
            } catch (PluginStoreException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Plugin store request interrupted: requestId={}, operation={}, attempt={}, durationMs={}, uri={}",
                        requestId, operation, attempts, elapsedMillis(startedAt), uri, e);
                throw new PluginStoreException("插件商店请求被中断", e);
            } catch (IOException e) {
                if (attempts < MAX_ATTEMPTS) {
                    log.warn("Plugin store network request will retry: requestId={}, operation={}, attempt={}, delayMs={}, uri={}, cause={}",
                            requestId, operation, attempts, backoffMillis(attempts), uri, rootMessage(e));
                    backoff(attempts);
                    continue;
                }
                log.warn("Plugin store network request failed: requestId={}, operation={}, attempts={}, durationMs={}, uri={}",
                        requestId, operation, attempts, elapsedMillis(startedAt), uri, e);
                throw new PluginStoreException("无法连接插件商店", e, uri);
            }
        }
    }

    private static void backoff(int attempts) {
        try {
            Thread.sleep(backoffMillis(attempts));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginStoreException("插件商店请求被中断", e);
        }
    }

    private static long backoffMillis(int attempts) {
        return 250L * (1L << (attempts - 1));
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static String responseSummary(Object body) {
        if (!(body instanceof String value)) {
            return body == null ? "<empty>" : "<" + body.getClass().getSimpleName() + ">";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        if (normalized.isEmpty()) return "<empty>";
        return normalized.length() <= MAX_LOG_BODY_LENGTH
                ? normalized : normalized.substring(0, MAX_LOG_BODY_LENGTH) + "…";
    }

    private static String logValue(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private URI endpoint(String path) {
        return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    private static URI normalizeBaseUri(String value) {
        String base = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.strip();
        return URI.create(base.endsWith("/") ? base : base + "/");
    }

    private static String path(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("plugin id/version is required");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String enc(String value) {
        return value == null || value.isBlank() ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String localeTag(Locale locale) {
        return locale == null ? "zh-CN" : locale.toLanguageTag();
    }

    private static void addQueryParameter(StringJoiner query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + enc(value));
        }
    }
}
