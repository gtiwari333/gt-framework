package gt.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Practical HTTP request handler examples.
 * Can be used with any of the three server implementations.
 */
public class HttpHandlerExamples {

    /**
     * Simple REST API handler with routing
     */
    public static class RestApiHandler implements HttpRequestHandler {
        private final Map<String, HandlerFunction> routes = new HashMap<>();

        public RestApiHandler() {
            // GET routes
            routes.put("GET|/", this::handleGetHome);
            routes.put("GET|/api/users", this::handleGetUsers);
            routes.put("GET|/api/health", this::handleHealth);

            // POST routes
            routes.put("POST|/api/users", this::handleCreateUser);
            routes.put("POST|/api/echo", this::handleEcho);

            // DELETE routes
            routes.put("DELETE|/api/users/.*", this::handleDeleteUser);
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            String key = request.getMethod() + "|" + request.getPath();

            // Try exact match first
            HandlerFunction handler = routes.get(key);

            // Try pattern match
            if (handler == null) {
                for (Map.Entry<String, HandlerFunction> entry : routes.entrySet()) {
                    String[] parts = entry.getKey().split("\\|");
                    if (parts.length == 2 && parts[0].equals(request.getMethod())) {
                        String pattern = parts[1].replace(".*", "");
                        if (request.getPath().matches(parts[1])) {
                            handler = entry.getValue();
                            break;
                        }
                    }
                }
            }

            if (handler != null) {
                return handler.handle(request);
            }

            return notFound();
        }

        private HttpResponse handleGetHome(HttpRequest req) {
            String html = "<h1>Welcome to REST API</h1>\n" +
                         "<p>Available endpoints:</p>\n" +
                         "<ul>\n" +
                         "<li>GET /api/users</li>\n" +
                         "<li>GET /api/health</li>\n" +
                         "<li>POST /api/users</li>\n" +
                         "</ul>";
            return jsonResponse(200, "{\"message\": \"Hello, API!\"}");
        }

        private HttpResponse handleGetUsers(HttpRequest req) {
            String json = "{\"users\": [{\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"}]}";
            return jsonResponse(200, json);
        }

        private HttpResponse handleCreateUser(HttpRequest req) {
            String body = req.getBody() != null ?
                new String(req.getBody(), StandardCharsets.UTF_8) : "{}";
            String response = "{\"id\": 3, \"name\": \"Created\", \"input\": " + body + "}";
            return jsonResponse(201, response);
        }

        private HttpResponse handleHealth(HttpRequest req) {
            return jsonResponse(200, "{\"status\": \"healthy\", \"timestamp\": " + System.currentTimeMillis() + "}");
        }

        private HttpResponse handleEcho(HttpRequest req) {
            String body = req.getBody() != null ?
                new String(req.getBody(), StandardCharsets.UTF_8) : "";
            return jsonResponse(200, "{\"echo\": \"" + escapeJson(body) + "\"}");
        }

        private HttpResponse handleDeleteUser(HttpRequest req) {
            String userId = req.getPath().replaceAll("/api/users/", "");
            return jsonResponse(200, "{\"deleted\": " + userId + "}");
        }

        private HttpResponse jsonResponse(int status, String json) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("Cache-Control", "no-cache");
            return new HttpResponse(status, headers, json.getBytes(StandardCharsets.UTF_8));
        }

        private HttpResponse notFound() {
            return jsonResponse(404, "{\"error\": \"Not found\"}");
        }

        private String escapeJson(String str) {
            return str.replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
        }
    }

    /**
     * Static file server
     */
    public static class StaticFileHandler implements HttpRequestHandler {
        private final String baseDir;

        public StaticFileHandler(String baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            if (!"GET".equals(request.getMethod())) {
                return methodNotAllowed();
            }

            try {
                String path = request.getPath();
                if (path.equals("/")) {
                    path = "/index.html";
                }

                java.nio.file.Path filePath = java.nio.file.Paths.get(baseDir, path);

                // Security: prevent directory traversal
                if (!filePath.normalize().startsWith(java.nio.file.Paths.get(baseDir).normalize())) {
                    return forbidden();
                }

                if (!java.nio.file.Files.exists(filePath)) {
                    return notFound();
                }

                byte[] content = java.nio.file.Files.readAllBytes(filePath);
                String contentType = guessContentType(path);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Cache-Control", "public, max-age=3600");

                return new HttpResponse(200, headers, content);
            } catch (Exception e) {
                System.err.println("Error reading file: " + e.getMessage());
                return serverError();
            }
        }

        private String guessContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            if (path.endsWith(".json")) return "application/json";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".gif")) return "image/gif";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".txt")) return "text/plain";
            return "application/octet-stream";
        }

        private HttpResponse notFound() {
            return textResponse(404, "404 Not Found");
        }

        private HttpResponse forbidden() {
            return textResponse(403, "403 Forbidden");
        }

        private HttpResponse methodNotAllowed() {
            return textResponse(405, "405 Method Not Allowed");
        }

        private HttpResponse serverError() {
            return textResponse(500, "500 Internal Server Error");
        }

        private HttpResponse textResponse(int status, String text) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "text/plain");
            return new HttpResponse(status, headers, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Middleware-style handler with logging and request/response filtering
     */
    public static class LoggingHandler implements HttpRequestHandler {
        private final HttpRequestHandler delegate;

        public LoggingHandler(HttpRequestHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            long startTime = System.nanoTime();

            System.out.printf("[%s] %s %s%n",
                new Date(), request.getMethod(), request.getPath());

            if (request.getBody() != null) {
                System.out.printf("  Body: %d bytes%n", request.getBody().length);
            }

            HttpResponse response = delegate.handle(request);

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf("  Response: %d (%dms)%n", response.getStatusCode(), elapsedMs);

            return response;
        }
    }

    /**
     * CORS-enabled handler
     */
    public static class CorsHandler implements HttpRequestHandler {
        private final HttpRequestHandler delegate;
        private final String allowedOrigin;

        public CorsHandler(HttpRequestHandler delegate, String allowedOrigin) {
            this.delegate = delegate;
            this.allowedOrigin = allowedOrigin;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            // Handle CORS preflight
            if ("OPTIONS".equals(request.getMethod())) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Access-Control-Allow-Origin", allowedOrigin);
                headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
                headers.put("Access-Control-Max-Age", "3600");
                return new HttpResponse(204, headers, null);
            }

            HttpResponse response = delegate.handle(request);
            response.getHeaders().put("Access-Control-Allow-Origin", allowedOrigin);

            return response;
        }
    }

    /**
     * Rate limiting handler (simple token bucket)
     */
    public static class RateLimitHandler implements HttpRequestHandler {
        private final HttpRequestHandler delegate;
        private final Map<String, TokenBucket> buckets = new HashMap<>();
        private final int requestsPerSecond;

        public RateLimitHandler(HttpRequestHandler delegate, int requestsPerSecond) {
            this.delegate = delegate;
            this.requestsPerSecond = requestsPerSecond;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            String clientIp = "unknown"; // In real use, extract from socket

            TokenBucket bucket = buckets.computeIfAbsent(clientIp, k ->
                new TokenBucket(requestsPerSecond, requestsPerSecond));

            if (bucket.tryConsume()) {
                return delegate.handle(request);
            } else {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Retry-After", "1");
                headers.put("Content-Type", "text/plain");
                return new HttpResponse(429, headers,
                    "Too Many Requests".getBytes(StandardCharsets.UTF_8));
            }
        }

        private static class TokenBucket {
            private long tokens;
            private long lastRefillTime = System.currentTimeMillis();
            private final int capacity;
            private final int refillRate;

            TokenBucket(int capacity, int refillRate) {
                this.capacity = capacity;
                this.refillRate = refillRate;
                this.tokens = capacity;
            }

            synchronized boolean tryConsume() {
                refill();
                if (tokens > 0) {
                    tokens--;
                    return true;
                }
                return false;
            }

            private void refill() {
                long now = System.currentTimeMillis();
                long timePassed = now - lastRefillTime;
                long tokensToAdd = (timePassed * refillRate) / 1000;
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }

    /**
     * Gzip compression handler
     */
    public static class CompressionHandler implements HttpRequestHandler {
        private final HttpRequestHandler delegate;

        public CompressionHandler(HttpRequestHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpResponse handle(HttpRequest request) {
            HttpResponse response = delegate.handle(request);

            String acceptEncoding = request.getHeader("accept-encoding");
            if (acceptEncoding != null && acceptEncoding.contains("gzip")) {
                try {
                    byte[] compressed = compress(response.getBody());
                    response.getHeaders().put("Content-Encoding", "gzip");
                    return new HttpResponse(response.getStatusCode(),
                        response.getHeaders(), compressed);
                } catch (Exception e) {
                    // Fallback to uncompressed
                    return response;
                }
            }

            return response;
        }

        private byte[] compress(byte[] data) throws Exception {
            if (data == null || data.length == 0) {
                return data;
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out);
            gzip.write(data);
            gzip.close();
            return out.toByteArray();
        }
    }

    // Usage example
    public static void main(String[] args) throws Exception {
        // Example: REST API with CORS and logging
        HttpRequestHandler api = new RestApiHandler();
        HttpRequestHandler withCors = new CorsHandler(api, "*");
        HttpRequestHandler withLogging = new LoggingHandler(withCors);
        HttpRequestHandler withCompression = new CompressionHandler(withLogging);

        ThreadPoolHttpServer server = new ThreadPoolHttpServer(8080, 20, withCompression);
        server.start();

        // Example: Static file server
        // HttpRequestHandler staticFiles = new StaticFileHandler("./public");
        // ThreadPoolHttpServer server = new ThreadPoolHttpServer(8080, 20, staticFiles);
        // server.start();
    }
}

interface HttpRequestHandler {
    HttpResponse handle(HttpRequest request);
}
