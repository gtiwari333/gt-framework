package gt.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Example handlers and interceptor patterns for the HTTP server.
 * Demonstrates how to use the router and interceptor system.
 */
public class HttpServerExamples {

    /**
     * Example 1: Simple REST API
     */
    public static class RestApiExample {
        public static void setupRoutes(HttpRouter router) {
            // GET /users
            router.get("^/users$", req -> jsonResponse(200, """
                {"users": [
                  {"id": 1, "name": "Alice", "email": "alice@example.com"},
                  {"id": 2, "name": "Bob", "email": "bob@example.com"}
                ]}
                """));

            // GET /users/{id}
            router.get("^/users/([0-9]+)$", req -> {
                String userId = extractPathParam(req.path(), "^/users/([0-9]+)$");
                return jsonResponse(200, """
                    {"id": %s, "name": "User %s", "email": "user%s@example.com"}
                    """.formatted(userId, userId, userId));
            });

            // POST /users
            router.post("^/users$", req -> {
                String body = req.body() != null ? new String(req.body(), StandardCharsets.UTF_8) : "{}";
                return jsonResponse(201, """
                    {"id": 123, "created": true, "data": %s}
                    """.formatted(body));
            });

            // PUT /users/{id}
            router.put("^/users/([0-9]+)$", req -> {
                String userId = extractPathParam(req.path(), "^/users/([0-9]+)$");
                return jsonResponse(200, """
                    {"id": %s, "updated": true}
                    """.formatted(userId));
            });

            // DELETE /users/{id}
            router.delete("^/users/([0-9]+)$", req -> {
                String userId = extractPathParam(req.path(), "^/users/([0-9]+)$");
                return jsonResponse(200, """
                    {"id": %s, "deleted": true}
                    """.formatted(userId));
            });
        }
    }

    /**
     * Example 2: Content-Type based conditional handling
     */
    public static class ContentTypeInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            String contentType = request.getHeader("content-type");

            // Validate content-type for POST/PUT requests
            if ("POST".equals(request.method()) || "PUT".equals(request.method())) {
                if (contentType == null) {
                    return new HttpResponse(400,
                        Map.of("Content-Type", "application/json"),
                        """
                        {"error": "Bad Request", "message": "Content-Type header required"}
                        """.getBytes(StandardCharsets.UTF_8));
                }

                // Validate JSON content type for API endpoints
                if (request.path().startsWith("/api/") && !contentType.contains("application/json")) {
                    return new HttpResponse(415,
                        Map.of("Content-Type", "application/json"),
                        """
                        {"error": "Unsupported Media Type", "expected": "application/json"}
                        """.getBytes(StandardCharsets.UTF_8));
                }
            }

            return chain.proceed(request);
        }
    }

    /**
     * Example 3: Request/Response compression negotiation
     */
    public static class CompressionInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            var response = chain.proceed(request);

            String acceptEncoding = request.getHeader("accept-encoding");
            if (acceptEncoding != null && acceptEncoding.contains("gzip") &&
                response.body() != null && response.body().length > 1024) {

                byte[] compressed = compress(response.body());
                Map<String, String> headers = new LinkedHashMap<>(response.headers());
                headers.put("Content-Encoding", "gzip");
                return new HttpResponse(response.statusCode(), headers, compressed);
            }

            return response;
        }

        private byte[] compress(byte[] data) {
            try {
                var out = new java.io.ByteArrayOutputStream();
                var gzip = new java.util.zip.GZIPOutputStream(out);
                gzip.write(data);
                gzip.close();
                return out.toByteArray();
            } catch (Exception e) {
                return data; // Fallback to uncompressed
            }
        }
    }

    /**
     * Example 4: Request validation interceptor
     * Checks headers, path parameters, and request body
     */
    public static class RequestValidationInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            // Validate user-agent header presence
            if (request.getHeader("user-agent") == null) {
                return new HttpResponse(400,
                    Map.of("Content-Type", "application/json"),
                    """
                    {"error": "Bad Request", "message": "User-Agent header is required"}
                    """.getBytes(StandardCharsets.UTF_8));
            }

            // Validate request body size for POST/PUT
            if (("POST".equals(request.method()) || "PUT".equals(request.method())) &&
                request.body() != null && request.body().length > 1_000_000) {

                return new HttpResponse(413,
                    Map.of("Content-Type", "application/json"),
                    """
                    {"error": "Payload Too Large", "maxSize": 1000000}
                    """.getBytes(StandardCharsets.UTF_8));
            }

            return chain.proceed(request);
        }
    }

    /**
     * Example 5: Custom error handling interceptor
     */
    public static final class ErrorHandlingInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            try {
                return chain.proceed(request);
            } catch (Exception e) {
                System.err.printf("Error handling %s %s: %s%n",
                    request.method(), request.path(), e.getMessage());

                return new HttpResponse(500,
                    Map.of("Content-Type", "application/json"),
                    """
                    {"error": "Internal Server Error", "message": "%s"}
                    """.formatted(e.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Example 6: Request deduplication for idempotent operations
     */
    public static final class IdempotencyKeyInterceptor implements HttpInterceptor {
        private final Map<String, HttpResponse> cache = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            String idempotencyKey = request.getHeader("idempotency-key");

            // Check cache for duplicate request
            if (idempotencyKey != null && cache.containsKey(idempotencyKey)) {
                return cache.get(idempotencyKey);
            }

            var response = chain.proceed(request);

            // Cache idempotent responses
            if (idempotencyKey != null && response.statusCode() >= 200 && response.statusCode() < 300) {
                cache.put(idempotencyKey, response);
            }

            return response;
        }
    }

    /**
     * Example 7: Custom header injection
     */
    public static final class SecurityHeadersInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            var response = chain.proceed(request);

            Map<String, String> headers = new LinkedHashMap<>(response.headers());
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("X-Frame-Options", "DENY");
            headers.put("X-XSS-Protection", "1; mode=block");
            headers.put("Strict-Transport-Security", "max-age=31536000");

            return new HttpResponse(response.statusCode(), headers, response.body());
        }
    }

    /**
     * Example 8: Performance monitoring interceptor
     */
    public static final class PerformanceMonitoringInterceptor implements HttpInterceptor {
        private final Map<String, Long> slowRequests = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            long start = System.nanoTime();
            var response = chain.proceed(request);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Track slow requests (> 1 second)
            if (elapsedMs > 1000) {
                slowRequests.put(request.path(), elapsedMs);
                System.out.printf("SLOW REQUEST: %s %s took %dms%n",
                    request.method(), request.path(), elapsedMs);
            }

            return response;
        }

        public void printSlowRequests() {
            slowRequests.forEach((path, duration) ->
                System.out.printf("  %s: %dms%n", path, duration));
        }
    }

    /**
     * Example 9: Complete application setup
     */
    public static void setupCompleteApplication() throws IOException {
        var router = new HttpRouter();

        // Setup REST API routes
        RestApiExample.setupRoutes(router);

        // Add interceptors in order (they will execute in reverse order in the chain)
        router.addInterceptor(new ErrorHandlingInterceptor());
        router.addInterceptor(new PerformanceMonitoringInterceptor());
        router.addInterceptor(new SecurityHeadersInterceptor());
        router.addInterceptor(new LoggingInterceptor());

        // Add conditional interceptors
        router.addInterceptor(new ConditionalInterceptor(
            req -> "POST".equals(req.method()) || "PUT".equals(req.method()),
            new ContentTypeInterceptor()
        ));

        router.addInterceptor(new ConditionalInterceptor(
            req -> req.path().startsWith("/api/"),
            new AuthenticationInterceptor()
        ));

        router.addInterceptor(new ConditionalInterceptor(
            req -> req.getHeader("accept-encoding") != null,
            new CompressionInterceptor()
        ));

        // Start server
        ThreadPoolHttpServer server = new ThreadPoolHttpServer(8080, 50, router);
        server.start();
    }

    /**
     * Example 10: Simple health check and metrics endpoint
     */
    public static void setupHealthAndMetrics(HttpRouter router) {
        var startTime = System.currentTimeMillis();
        var requestCount = new java.util.concurrent.atomic.AtomicLong(0);

        router.get("^/health$", req -> {
            requestCount.incrementAndGet();
            return jsonResponse(200, """
                {
                  "status": "UP",
                  "timestamp": %d,
                  "uptime": %d
                }
                """.formatted(System.currentTimeMillis(), System.currentTimeMillis() - startTime));
        });

        router.get("^/metrics$", req -> {
            return jsonResponse(200, """
                {
                  "requests": %d,
                  "uptime": %d,
                  "memory": {
                    "used": %d,
                    "max": %d
                  }
                }
                """.formatted(
                    requestCount.get(),
                    System.currentTimeMillis() - startTime,
                    Runtime.getRuntime().totalMemory(),
                    Runtime.getRuntime().maxMemory()
                ));
        });
    }

    // Helper methods
    private static HttpResponse jsonResponse(int status, String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        return new HttpResponse(status, headers, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String extractPathParam(String path, String pattern) {
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(path);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static void main(String[] args) throws IOException {
        setupCompleteApplication();
    }
}
