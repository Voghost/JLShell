package com.jlshell.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import com.jlshell.api.server.dispatch.CapabilityInvokeMethod;
import com.jlshell.api.server.dispatch.CapabilityListMethod;
import com.jlshell.api.server.dispatch.MethodDispatcher;
import com.jlshell.plugin.api.rpc.SessionPluginActivator;
import com.jlshell.api.server.jsonrpc.RpcHandler;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiMethod;
import com.jlshell.program.api.ProgramApiRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部 API server：JDK {@link HttpServer}，绑 127.0.0.1，bearer token 鉴权。
 *
 * <p>把 {@link CapabilityBus}（插件能力透传）与 {@link ProgramApiRegistry}（宿主 SPI 方法）
 * 统一注册到 {@link MethodDispatcher}，对外暴露 /rpc 单端点。
 */
public final class ApiServer {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private final ApiServerConfig config;
    private final MethodDispatcher dispatcher;
    private HttpServer httpServer;
    private int actualPort = -1;

    public ApiServer(ApiServerConfig config, CapabilityBus bus, ProgramApiRegistry programApiRegistry) {
        this(config, bus, programApiRegistry, SessionPluginActivator.noop());
    }

    public ApiServer(ApiServerConfig config, CapabilityBus bus, ProgramApiRegistry programApiRegistry,
                     SessionPluginActivator sessionPluginActivator) {
        this.config = config;
        this.dispatcher = new MethodDispatcher();
        // 透传插件能力
        dispatcher.register("capability.invoke", new CapabilityInvokeMethod(bus, sessionPluginActivator));
        dispatcher.register("capability.list", new CapabilityListMethod(bus));
        // Program API 由 app 的 ServiceLoader SPI 提供。
        Map<String, ProgramApiMethod> programMethods = programApiRegistry.methods();
        programMethods.forEach((method, handler) ->
                dispatcher.register(method, handler::handle));
        ProgramApiMethod disconnectMethod = programMethods.get(ProgramApiCatalog.SESSION_DISCONNECT);
        if (disconnectMethod != null) {
            dispatcher.register(ProgramApiCatalog.SESSION_DISCONNECT, params ->
                    disconnectMethod.handle(params)
                            .thenCompose(result -> sessionPluginActivator.deactivate(sessionId(params))
                                    .thenApply(ignored -> result)));
        }
    }

    private static String sessionId(com.google.gson.JsonElement params) {
        if (params == null || !params.isJsonObject()) return null;
        com.google.gson.JsonObject object = params.getAsJsonObject();
        if (!object.has("sessionId") || object.get("sessionId").isJsonNull()) return null;
        return object.get("sessionId").getAsString();
    }

    public void start() throws IOException {
        if (!config.enabled()) return;
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
        httpServer.createContext("/rpc", new RpcHandler(config.token(), dispatcher));
        httpServer.setExecutor(null); // default executor
        httpServer.start();
        actualPort = httpServer.getAddress().getPort();
        log.info("External API listening on 127.0.0.1:{}", actualPort);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    public int port() {
        return actualPort;
    }

    public String token() {
        return config.token();
    }

    public boolean enabled() {
        return config.enabled();
    }
}
