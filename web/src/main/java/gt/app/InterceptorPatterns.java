package gt.app;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive interceptor implementation guide.
 * Shows various patterns for implementing interceptors for different use cases.
 */
public class InterceptorPatterns {

    /**
     * Pattern 1: Simple passthrough interceptor
     */
    public static final class NoOpInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            return chain.proceed(request);
        }
    }

    /**
     * Pattern 2: Pre-processing only
     */
    public static final class RequestTimestampInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            System.out.printf("[%d] %s %s%n",
                System.currentTimeMillis(),
                request.method(),
                request.path());
            return chain.proceed(request);
        }
    }

    /**
     * Pattern 3: Post-processing only
     */
    public static final class ResponseHeaderInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            var response = chain.proceed(request);
            Map<String, String> headers = new LinkedHashMap<>(response.headers());
            headers.put("X-Processed-At", String.valueOf(System.currentTimeMillis()));
            return new HttpResponse(response.statusCode(), headers, response.body());
        }
    }

    /**
     * Pattern 4: Request/response inspection
     */
    public static final class RequestResponseInspectorInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            // Before
            System.out.println("→ Request: " + request.method() + " " + request.path());
            request.headers().forEach((k, v) ->
                System.out.println("  Header: " + k + "=" + v));
            if (request.body() != null) {
                System.out.println("  Body size: " + request.body().length + " bytes");
            }

            var response = chain.proceed(request);

            // After
            System.out.println("← Response: " + response.statusCode());
            response.headers().forEach((k, v) ->
                System.out.println("  Header: " + k + "=" + v));
            if (response.body() != null) {
                System.out.println("  Body size: " + response.body().length + " bytes");
            }

            return response;
        }
    }

    /**
     * Pattern 5: Request transformation
     */
    public static final class RequestNormalizationInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            // Normalize path (remove trailing slashes, lowercase)
            String normalizedPath = request.path().replaceAll("/$", "").toLowerCase();

            if (!normalizedPath.equals(request.path())) {
                // Redirect to normalized path
                return new HttpResponse(301,
                    Map.of("Location", normalizedPath.isEmpty() ? "/" : normalizedPath),
                    null);
            }

            return chain.proceed(request);
        }
    }

    /**
     * Pattern 6: Response transformation
     */
    public static final class ResponseWrapperInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            var response = chain.proceed(request);

            // Wrap JSON responses
            if (response.headers().get("Content-Type").contains("application/json")) {
                String bodyStr = response.body() != null ?
                    new String(response.body(), StandardCharsets.UTF_8) : "null";

                String wrapped = """
                    {
                      "success": %b,
                      "statusCode": %d,
                      "data": %s
                    }
                    """.formatted(response.statusCode() >= 200 && response.statusCode() < 300,
                                 response.statusCode(), bodyStr);

                return new HttpResponse(response.statusCode(),
                    response.headers(),
                    wrapped.getBytes(StandardCharsets.UTF_8));
            }

            return response;
        }
    }

    /**
     * Pattern 7: Short-circuit without calling next handler
     */
    public static final class CacheInterceptor implements HttpInterceptor {
        private final Map<String, CachedResponse> cache = new ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            // Only cache GET requests
            if (!"GET".equals(request.method())) {
                return chain.proceed(request);
            }

            // Check cache
            CachedResponse cached = cache.get(request.path());
            if (cached != null && !cached.isExpired()) {
                System.out.println("Cache HIT: " + request.path());
                return cached.response();
            }

            // Cache miss - proceed to handler
            System.out.println("Cache MISS: " + request.path());
            var response = chain.proceed(request);

            // Cache successful responses for 60 seconds
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                cache.put(request.path(), new CachedResponse(response, System.currentTimeMillis() + 60000));
            }

            return response;
        }

        record CachedResponse(HttpResponse response, long expiresAt) {
            boolean isExpired() {
                return System.currentTimeMillis() > expiresAt;
            }
        }
    }

    /**
     * Pattern 8: Exception handling and error response
     */
    public static final class ExceptionHandlingInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            try {
                return chain.proceed(request);
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return new HttpResponse(500,
                    Map.of("Content-Type", "application/json"),
                    """
                    {"error": "Internal Server Error", "message": "%s"}
                    """.formatted(e.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Pattern 9: Rate limiting with per-endpoint configuration
     */
    public static final class PerEndpointRateLimitInterceptor implements HttpInterceptor {
        private final Map<String, Integer> rateLimits = Map.ofEntries(
            Map.entry("^/api/expensive$", 10),    // 10 req/sec
            Map.entry("^/api/standard.*", 100),   // 100 req/sec
            Map.entry("^/api/bulk.*", 1000)       // 1000 req/sec
        );

        private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            // Find applicable rate limit
            int limit = rateLimits.entrySet().stream()
                .filter(e -> java.util.regex.Pattern.matches(e.getKey(), request.path()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(100);

            String key = request.path() + ":" + getClientId();
            TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(limit, limit));

            if (!bucket.tryConsume()) {
                return new HttpResponse(429,
                    Map.of("Content-Type", "application/json", "Retry-After", "1"),
                    """
                    {"error": "Rate limit exceeded", "limit": %d}
                    """.formatted(limit).getBytes(StandardCharsets.UTF_8));
            }

            return chain.proceed(request);
        }

        private String getClientId() {
            return "default"; // In production: extract from request
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
                long now = System.currentTimeMillis();
                long timePassed = now - lastRefillTime;
                tokens = Math.min(capacity, tokens + (timePassed * refillRate) / 1000);
                lastRefillTime = now;

                if (tokens > 0) {
                    tokens--;
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * Pattern 10: Request/response metrics collection
     */
    public static final class MetricsCollectorInterceptor implements HttpInterceptor {
        private final Map<String, Metrics> metrics = new ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            long start = System.nanoTime();
            var response = chain.proceed(request);
            long elapsedNs = System.nanoTime() - start;

            // Record metrics
            String key = request.method() + " " + request.path();
            metrics.computeIfAbsent(key, k -> new Metrics(key))
                   .record(response.statusCode(), elapsedNs);

            return response;
        }

        public void printMetrics() {
            metrics.forEach((key, m) -> System.out.println(m.toString()));
        }

        static class Metrics {
            String path;
            long count;
            long totalNs;
            long minNs = Long.MAX_VALUE;
            long maxNs = 0;
            Map<Integer, Long> statusCodes = new ConcurrentHashMap<>();

            Metrics(String path) {
                this.path = path;
            }

            synchronized void record(int status, long nanos) {
                count++;
                totalNs += nanos;
                minNs = Math.min(minNs, nanos);
                maxNs = Math.max(maxNs, nanos);
                statusCodes.compute(status, (k, v) -> (v == null) ? 1 : v + 1);
            }

            @Override
            public String toString() {
                double avgMs = (totalNs / count) / 1_000_000.0;
                return String.format("%s: %d requests, avg=%.2fms, min=%.2fms, max=%.2fms, statuses=%s",
                    path, count, avgMs, minNs / 1_000_000.0, maxNs / 1_000_000.0, statusCodes);
            }
        }

        public long getRequestCount() {
            return metrics.values().stream().mapToLong(m -> m.count).sum();
        }
    }

    /**
     * Pattern 11: Header-based routing/handling
     */
    public static final class HeaderBasedInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            String apiVersion = request.getHeader("x-api-version");

            // Route based on API version
            if ("v2".equals(apiVersion)) {
                // Could redirect to different handler or transform response
                System.out.println("Using API v2");
            } else if ("v1".equals(apiVersion) || apiVersion == null) {
                System.out.println("Using API v1 (default)");
            }

            return chain.proceed(request);
        }
    }

    /**
     * Pattern 12: Request body validation with early termination
     */
    public static final class RequestBodyValidatorInterceptor implements HttpInterceptor {
        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            if (("POST".equals(request.method()) || "PUT".equals(request.method())) &&
                request.body() != null) {

                String contentType = request.getHeader("content-type");

                // Validate JSON
                if (contentType != null && contentType.contains("application/json")) {
                    if (!isValidJson(request.body())) {
                        return new HttpResponse(400,
                            Map.of("Content-Type", "application/json"),
                            """
                            {"error": "Bad Request", "message": "Invalid JSON"}
                            """.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            return chain.proceed(request);
        }

        private boolean isValidJson(byte[] body) {
            try {
                // Simple check: try to parse with minimal JSON parser
                String str = new String(body, StandardCharsets.UTF_8).trim();
                return (str.startsWith("{") && str.endsWith("}")) ||
                       (str.startsWith("[") && str.endsWith("]"));
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Pattern 13: Middleware composition - chaining multiple concerns
     */
    public static void setupComplexInterceptorChain(HttpRouter router) {
        // Order matters: executed in reverse

        // 1. Last to execute (closest to handler)
        router.addInterceptor(new RequestBodyValidatorInterceptor());

        // 2. Metrics collection
        var metricsCollector = new MetricsCollectorInterceptor();
        router.addInterceptor(metricsCollector);

        // 3. Rate limiting
        router.addInterceptor(new PerEndpointRateLimitInterceptor());

        // 4. Caching
        router.addInterceptor(new CacheInterceptor());

        // 5. Exception handling
        router.addInterceptor(new ExceptionHandlingInterceptor());

        // 6. First to execute (farthest from handler)
        router.addInterceptor(new LoggingInterceptor());

        // Execution order: LoggingInterceptor → ExceptionHandling → Cache → RateLimit → Metrics → Validation → Handler
    }

    /**
     * Pattern 14: Dynamic interceptor disabling
     */
    public static final class ConditionalEnablingInterceptor implements HttpInterceptor {
        private final HttpInterceptor wrapped;
        private final java.util.function.Predicate<HttpRequest> enabledWhen;

        public ConditionalEnablingInterceptor(HttpInterceptor wrapped,
                                              java.util.function.Predicate<HttpRequest> enabledWhen) {
            this.wrapped = wrapped;
            this.enabledWhen = enabledWhen;
        }

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            if (enabledWhen.test(request)) {
                return wrapped.intercept(request, chain);
            }
            return chain.proceed(request);
        }
    }

    /**
     * Pattern 15: Request deduplication with Idempotency-Key
     */
    public static final class IdempotencyInterceptor implements HttpInterceptor {
        private final Map<String, HttpResponse> responsecache = new ConcurrentHashMap<>();

        @Override
        public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
            String idempotencyKey = request.getHeader("idempotency-key");

            if (idempotencyKey != null) {
                // Check if we've seen this request before
                HttpResponse cached = responseCache.get(idempotencyKey);
                if (cached != null) {
                    System.out.println("Returning cached idempotent response");
                    return cached;
                }
            }

            var response = chain.proceed(request);

            // Cache successful responses for idempotent operations
            if (idempotencyKey != null && response.statusCode() >= 200 && response.statusCode() < 300) {
                responseCache.put(idempotencyKey, response);
            }

            return response;
        }
    }

    /**
     * Demonstrates interceptor usage
     */
    public static void main(String[] args) throws IOException {
        var router = new HttpRouter();

        // Basic route
        router.get("^/$", req -> new HttpResponse(200,
            Map.of("Content-Type", "text/plain"),
            "Hello".getBytes(StandardCharsets.UTF_8)));

        // Example interceptor chains

        // Chain 1: Simple logging and CORS
        router.addInterceptor(new LoggingInterceptor());
        router.addInterceptor(new CorsInterceptor("*"));

        // Chain 2: Validation and caching
        router.addInterceptor(new RequestBodyValidatorInterceptor());
        router.addInterceptor(new CacheInterceptor());

        // Chain 3: Metrics and rate limiting
        var metrics = new MetricsCollectorInterceptor();
        router.addInterceptor(metrics);
        router.addInterceptor(new RateLimitInterceptor(1000));

        // Start server
        ThreadPoolHttpServer server = new ThreadPoolHttpServer(8080, 50, router);
        new Thread(server::start).start();

        // Print metrics periodically
        new Timer().scheduleAtFixedRate(
            () -> metrics.printMetrics(), 10000, 10000);
    }
}
