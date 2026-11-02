package com.uyirpath.dispatch.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the static frontend and prevents path traversal. */
public class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "application/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8")
    );

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.equals("/")) requestPath = "/index.html";
            Path candidate = root.resolve(requestPath.substring(1)).normalize();
            if (!candidate.startsWith(root)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!Files.exists(candidate) && !requestPath.contains(".")) {
                candidate = root.resolve("index.html");
            }
            if (!Files.isRegularFile(candidate)) {
                byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(notFound); }
                return;
            }
            String name = candidate.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            String type = TYPES.getOrDefault(ext, "application/octet-stream");
            byte[] bytes = Files.readAllBytes(candidate);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.getResponseHeaders().set("Cache-Control",
                    ext.equals("html") ? "no-cache" : "public, max-age=300");
            exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : bytes.length);
            if (!"HEAD".equals(method)) {
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            }
        } finally {
            exchange.close();
        }
    }
}
