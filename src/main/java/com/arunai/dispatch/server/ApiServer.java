package com.arunai.dispatch.server;

import com.arunai.dispatch.controller.ApiController;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java built-in HTTP server with REST and static contexts. */
public class ApiServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public ApiServer(int port, ApiController api, Path frontendDir) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newFixedThreadPool(16, r -> {
            Thread thread = new Thread(r, "arunai-http");
            thread.setDaemon(false);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api", api);
        server.createContext("/", new StaticHandler(frontendDir));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(1);
        executor.shutdownNow();
    }
}
