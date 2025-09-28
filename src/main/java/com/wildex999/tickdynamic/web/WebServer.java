package com.wildex999.tickdynamic.web;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.wildex999.tickdynamic.TickDynamicMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class WebServer {
    private final TickDynamicMod mod;
    private final String bind;
    private final int port;
    private final String token;
    private HttpServer server;
    private final AtomicReference<String> latestSnapshot = new AtomicReference<>("{}\n");
    // Optional runtime flags
    private final boolean corsEnabled = Boolean.parseBoolean(System.getProperty("tickdynamic.web.cors.enabled", "false"));
    private final String corsOrigin = System.getProperty("tickdynamic.web.cors.origin", "*");
    private final boolean gzipEnabled = Boolean.parseBoolean(System.getProperty("tickdynamic.web.gzip.enabled", "true"));

    public WebServer(TickDynamicMod mod, String bind, int port, String token) {
        this.mod = mod;
        this.bind = bind;
        this.port = port;
        this.token = token == null ? "" : token;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.createContext("/api/metrics", new MetricsHandler());
        server.createContext("/health", exchange -> { writeText(exchange, 200, "ok\n"); });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if(server != null) { server.stop(0); server = null; }
    }

    public void updateSnapshot(String json) {
        if(json == null) json = "{}";
        latestSnapshot.set(json);
    }

    private boolean authorized(HttpExchange exchange) {
        if(token.isEmpty()) return true;
        String q = exchange.getRequestURI().getQuery();
        if(q != null) {
            for(String part : q.split("&")) {
                int i = part.indexOf('=');
                String k = i>0?part.substring(0,i):part;
                String v = i>0?part.substring(i+1):"";
                if(k.equals("token") && v.equals(token)) return true;
            }
        }
        String hdr = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if(token.equals(hdr)) return true;
        // Support Authorization: Bearer <token>
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if(auth != null) {
            String trimmed = auth.trim();
            if(trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String t = trimmed.substring(7).trim();
                if(token.equals(t)) return true;
            }
        }
        return false;
    }

    private void applyCommonHeaders(HttpExchange exchange) {
        // Security and cache headers
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if(corsEnabled) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
            exchange.getResponseHeaders().set("Vary", "Origin");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-Auth-Token, Authorization, Content-Type");
        }
    }

    private void writeWithOptionalGzip(HttpExchange exchange, int code, String contentType, byte[] bytes) throws IOException {
        applyCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Optional gzip if requested and beneficial
        byte[] payload = bytes;
        boolean gzip = false;
        if(gzipEnabled && bytes != null && bytes.length > 256) {
            String ae = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if(ae != null && ae.toLowerCase().contains("gzip")) {
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(bytes.length);
                    try(java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(bos)) {
                        gos.write(bytes);
                    }
                    payload = bos.toByteArray();
                    gzip = true;
                } catch(Throwable t) {
                    // fall back to plain payload
                    payload = bytes;
                    gzip = false;
                }
            }
        }
        if(gzip) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(code, payload.length);
        try(OutputStream os = exchange.getResponseBody()) { os.write(payload); }
    }

    private void unauthorized(HttpExchange exchange) throws IOException {
        applyCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        byte[] body = "unauthorized\n".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, body.length);
        try(OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    private void methodNotAllowed(HttpExchange exchange) throws IOException {
        applyCommonHeaders(exchange);
        byte[] body = "method not allowed\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(405, body.length);
        try(OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    private void preflightOptions(HttpExchange exchange) throws IOException {
        applyCommonHeaders(exchange);
        // No body for preflight
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void writeText(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writeWithOptionalGzip(exchange, code, "text/plain; charset=utf-8", bytes);
    }

    private void writeJson(HttpExchange exchange, int code, String body) throws IOException {
        // Debug log for JSON output (guarded)
        if (TickDynamicMod.debug && body != null && body.length() > 0) {
            System.out.println("[TickDynamic-Web] Sending JSON (" + body.length() + " bytes)");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        writeWithOptionalGzip(exchange, code, "application/json; charset=utf-8", bytes);
    }

    private class RootHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { preflightOptions(exchange); return; }
            if(!authorized(exchange)) { unauthorized(exchange); return; }
            if(!exchange.getRequestMethod().equalsIgnoreCase("GET")) { methodNotAllowed(exchange); return; }
            String path = exchange.getRequestURI().getPath();
            if(path.equals("/") || path.equals("/index.html")) {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
                    if(is == null) { writeText(exchange, 404, "missing dashboard\n"); return; }
                    byte[] buf = readAll(is);
                    writeWithOptionalGzip(exchange, 200, "text/html; charset=utf-8", buf);
                }
                return;
            }
            writeText(exchange, 404, "not found\n");
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { preflightOptions(exchange); return; }
            if(!authorized(exchange)) { unauthorized(exchange); return; }
            if(!exchange.getRequestMethod().equalsIgnoreCase("GET")) { methodNotAllowed(exchange); return; }
            writeJson(exchange, 200, latestSnapshot.get());
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            while((len = is.read(buf)) != -1) { out.write(buf, 0, len); }
        } finally {
            try { is.close(); } catch (IOException ignore) {}
        }
        return out.toByteArray();
    }
}
