package com.jlshell.plugin.loader.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.jlshell.plugin.api.PluginScope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PluginStoreClientTest {

    private ExecutorService executor;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void searchUsesNormalizedContractParameters() {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server.createContext("/api/v1/plugins", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"content":[],"number":0,"size":20,"totalElements":0,"totalPages":0}
                    """);
        });
        server.start();

        PluginStorePage page = client().search(new PluginStoreSearch(
                "terminal tools", PluginScope.SESSION, "0.1.0.RELEASE",
                Locale.SIMPLIFIED_CHINESE, 0, 20, PluginStoreSearch.Sort.UPDATED)).join();

        assertThat(page.content()).isEmpty();
        assertThat(rawQuery.get())
                .contains("query=terminal+tools")
                .contains("scope=SESSION")
                .contains("hostVersion=0.1.0")
                .contains("locale=zh-CN")
                .contains("page=0")
                .contains("size=20")
                .contains("sort=updated");
    }

    @Test
    void invalidJsonFailureKeepsRequestUriForDiagnostics() {
        server.createContext("/api/v1/plugins", exchange -> respond(exchange, 200, "not-json"));
        server.start();

        Throwable error;
        try {
            client().search(PluginStoreSearch.initial("0.1.0", Locale.SIMPLIFIED_CHINESE)).join();
            throw new AssertionError("Expected search to fail");
        } catch (CompletionException e) {
            error = e.getCause();
        }

        assertThat(error).isInstanceOf(PluginStoreException.class);
        PluginStoreException storeError = (PluginStoreException) error;
        assertThat(storeError.uri()).isNotNull();
        assertThat(storeError.uri().getPath()).isEqualTo("/api/v1/plugins");
    }

    private PluginStoreClient client() {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(executor)
                .build();
        return new PluginStoreClient(baseUri, httpClient, executor);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
