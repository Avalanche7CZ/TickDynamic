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
        return token.equals(hdr);
    }

    private static void writeText(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try(OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static void writeJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try(OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private class RootHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if(!authorized(exchange)) { writeText(exchange, 401, "unauthorized\n"); return; }
            if(!exchange.getRequestMethod().equalsIgnoreCase("GET")) { writeText(exchange, 405, "method not allowed\n"); return; }
            String path = exchange.getRequestURI().getPath();
            if(path.equals("/") || path.equals("/index.html")) {
                InputStream is = getClass().getClassLoader().getResourceAsStream("web/index.html");
                if(is == null) { writeText(exchange, 404, "missing dashboard\n"); return; }
                byte[] buf = readAll(is);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, buf.length);
                try(OutputStream os = exchange.getResponseBody()) { os.write(buf); }
                return;
            }
            writeText(exchange, 404, "not found\n");
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if(!authorized(exchange)) { writeText(exchange, 401, "unauthorized\n"); return; }
            if(!exchange.getRequestMethod().equalsIgnoreCase("GET")) { writeText(exchange, 405, "method not allowed\n"); return; }
            writeJson(exchange, 200, latestSnapshot.get());
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        int len, total = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while((len = is.read(buf)) != -1) { out.write(buf, 0, len); total += len; }
        return out.toByteArray();
    }
}
