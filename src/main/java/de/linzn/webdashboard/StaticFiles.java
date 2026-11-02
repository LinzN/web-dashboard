/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.webdashboard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Serves the dashboard files from the web folder of the plugin (e.g. the export of Bootstrap Studio). Changed
 * files are picked up right away. Paths outside the folder, also through symbolic links, are refused. Text files are sent
 * gzip compressed when the browser accepts it; every file has an ETag, so unchanged files are answered with 304.
 */
final class StaticFiles implements HttpHandler {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=UTF-8"), Map.entry("css", "text/css; charset=UTF-8"),
            Map.entry("js", "text/javascript; charset=UTF-8"), Map.entry("json", "application/json; charset=UTF-8"),
            Map.entry("webmanifest", "application/manifest+json"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"), Map.entry("txt", "text/plain; charset=UTF-8"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"));

    private record Content(byte[] data, byte[] gzip, String etag, String type) {
    }

    private final String contextPath;
    private final Path folder;
    private final String apiBase;
    /* gzip and ETag are computed again only when a file changed */
    private record Cached(long modified, long size, Content content) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    StaticFiles(String contextPath, Path folder, String apiBase) {
        this.contextPath = contextPath.endsWith("/") ? contextPath : contextPath + "/";
        this.folder = folder.toAbsolutePath().normalize();
        this.apiBase = apiBase;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String raw = exchange.getRequestURI().getRawPath();
            if (!this.apiBase.isEmpty() && (raw.equals(this.apiBase) || raw.startsWith(this.apiBase + "/"))) {
                /* an API module that does not exist: answer like the API, not with a page */
                this.send(exchange, 404, "application/json; charset=UTF-8",
                        ("{\"request\":\"" + raw.replace("\"", "") + "\",\"payload\":{},\"error\":404}").getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String relative = this.relativePath(raw);
            if (relative == null) {
                this.send(exchange, 400, "text/plain; charset=UTF-8", "Bad path".getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }
            Content content = this.load(relative);
            if (content == null) {
                this.send(exchange, 404, "text/plain; charset=UTF-8", "Not found".getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("ETag", content.etag());
            headers.set("Vary", "Accept-Encoding");
            String cache = cachePolicy(relative, exchange.getRequestURI().getRawQuery());
            String match = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (match != null && match.contains(content.etag())) {
                headers.set("Cache-Control", cache);
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            boolean gzip = content.gzip() != null && accepts(exchange, "gzip");
            if (gzip) {
                headers.set("Content-Encoding", "gzip");
            }
            byte[] body = gzip ? content.gzip() : content.data();
            if (method.equals("HEAD")) {
                this.headers(headers, content.type(), cache);
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            this.send(exchange, 200, content.type(), body, cache);
        }
    }

    /**
     * @return path below the dashboard folder ("index.html", "assets/js/x.js"), null if it leaves the folder
     */
    private String relativePath(String raw) {
        String path = raw.startsWith(this.contextPath) ? raw.substring(this.contextPath.length())
                : raw.equals(this.contextPath.substring(0, this.contextPath.length() - 1)) ? "" : raw.replaceFirst("^/", "");
        String decoded;
        try {
            decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.indexOf('\0') >= 0 || decoded.contains("\\")) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (String part : decoded.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || part.startsWith(".")) {
                /* no parent folders and no hidden files */
                return null;
            }
            if (!clean.isEmpty()) {
                clean.append('/');
            }
            clean.append(part);
        }
        if (clean.isEmpty() || decoded.endsWith("/")) {
            clean.append(clean.isEmpty() ? "" : "/").append("index.html");
        }
        return clean.toString();
    }

    private Content load(String relative) throws IOException {
        Path file = this.folder.resolve(relative).normalize();
        if (!file.startsWith(this.folder) || !Files.isRegularFile(file)) {
            return null;
        }
        /* a symbolic link must not lead out of the folder */
        Path real = file.toRealPath();
        if (!real.startsWith(this.folder.toRealPath())) {
            return null;
        }
        long modified = Files.getLastModifiedTime(real).toMillis();
        long size = Files.size(real);
        Cached cached = this.cache.get(relative);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached.content();
        }
        Content loaded = content(relative, Files.readAllBytes(real));
        this.cache.put(relative, new Cached(modified, size, loaded));
        return loaded;
    }

    private static Content content(String relative, byte[] data) throws IOException {
        String extension = relative.contains(".") ? relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = TYPES.getOrDefault(extension, "application/octet-stream");
        byte[] gzip = null;
        if ((type.startsWith("text/") || type.contains("json") || type.contains("svg") || extension.equals("ttf") || extension.equals("eot"))
                && data.length > 1024) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(data);
            }
            gzip = out.toByteArray();
        }
        return new Content(data, gzip, etag(data), type);
    }

    private static String etag(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return "\"" + HexFormat.of().formatHex(hash, 0, 12) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            return "\"" + data.length + "\"";
        }
    }

    /**
     * Pages and the service worker are checked on every load (ETag); files with a version hash in the URL
     * ("?h=...") are cached for a year. "private": the files are behind the login.
     */
    private static String cachePolicy(String relative, String query) {
        if (relative.endsWith(".html") || relative.endsWith("serviceWorker.js") || relative.endsWith("manifest.json")) {
            return "private, no-cache";
        }
        if (query != null && (query.startsWith("h=") || query.contains("&h="))) {
            return "private, max-age=31536000, immutable";
        }
        return "private, max-age=3600";
    }

    private static boolean accepts(HttpExchange exchange, String encoding) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(encoding);
    }

    private void headers(Headers headers, String type, String cache) {
        headers.set("Content-Type", type);
        headers.set("Cache-Control", cache);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "SAMEORIGIN");
        headers.set("Referrer-Policy", "same-origin");
    }

    private void send(HttpExchange exchange, int status, String type, byte[] body, String cache) throws IOException {
        this.headers(exchange.getResponseHeaders(), type, cache);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
