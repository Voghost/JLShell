package com.jlshell.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import com.jlshell.api.server.dispatch.CapabilityInvokeMethod;
import com.jlshell.api.server.dispatch.CapabilityListMethod;
import com.jlshell.api.server.dispatch.HostMethods;
import com.jlshell.api.server.dispatch.MethodDispatcher;
import com.jlshell.api.server.jsonrpc.RpcHandler;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部 API server：JDK {@link HttpServer}，绑 127.0.0.1，bearer token 鉴权。
 *
 * <p>把 {@link CapabilityBus}（插件能力透传）与 {@link HostMethods}（内置 host method）
 * 统一注册到 {@link MethodDispatcher}，对外暴露 /rpc 单端点。
 */
public final class ApiServer {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    private final ApiServerConfig config;
    private final MethodDispatcher dispatcher;
    private HttpServer httpServer;
    private int actualPort = -1;

    public ApiServer(ApiServerConfig config, CapabilityBus bus, HostMethods hostMethods) {
        this.config = config;
        this.dispatcher = new MethodDispatcher();
        // 透传插件能力
        dispatcher.register("capability.invoke", new CapabilityInvokeMethod(bus));
        dispatcher.register("capability.list", new CapabilityListMethod(bus));
        // 内置 host method
        dispatcher.register("session.connect", hostMethods::sessionConnect);
        dispatcher.register("session.disconnect", hostMethods::sessionDisconnect);
        dispatcher.register("session.list", hostMethods::sessionList);
        dispatcher.register("session.info", hostMethods::sessionInfo);
        dispatcher.register("command.run", hostMethods::commandRun);
        dispatcher.register("api.token", hostMethods::apiToken);
        dispatcher.register("api.methods", hostMethods::apiMethods);
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
