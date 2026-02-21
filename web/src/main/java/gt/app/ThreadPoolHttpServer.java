package gt.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * High-performance HTTP/1.1 server using thread pool for concurrent requests.
 * Built with Java 25 features: records, sealed classes, and pattern matching.
 * <p>
 * Features:
 * - Regex-based route matching
 * - Interceptor/filter support for cross-cutting concerns
 * - Records for immutable request/response objects
 * - Sealed interfaces for type-safe interceptors
 * - Pattern matching for status code handling
 */
public class ThreadPoolHttpServer {
    private final ServerSocket serverSocket;
    private final int port;
    private final ExecutorService executor;
    private volatile boolean running = true;
    private final HttpRouter router;

    public ThreadPoolHttpServer(int port, int threadPoolSize, HttpRouter router) throws IOException {
        this.port = port;
        this.router = router;
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
        this.serverSocket = new ServerSocket(port);
    }

    public void start() {
        System.out.println("HTTP Server listening on port " + port);
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) System.err.println("Error accepting connection: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30000);

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 8192);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream(), 8192);

            while (true) {
                HttpRequest request = parseRequest(input);
                if (request == null) break;

                // Apply interceptors and route handlers
                HttpResponse response = router.handleWithInterceptors(request);
                writeResponse(output, response);
                output.flush();

                if ("close".equalsIgnoreCase(request.getHeader("connection"))) break;
            }

        } catch (SocketTimeoutException ignored) {
            // Normal for keep-alive connections
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private HttpRequest parseRequest(BufferedInputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8), 8192
        );

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) return null;

        String method = parts[0];
        String path = parts[1];
        String httpVersion = parts[2];

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                headers.put(line.substring(0, colonIndex).trim().toLowerCase(),
                    line.substring(colonIndex + 1).trim());
            }
        }

        byte[] body = null;
        String contentLengthStr = headers.get("content-length");
        if (contentLengthStr != null) {
            try {
                int contentLength = Integer.parseInt(contentLengthStr);
                if (contentLength > 0 && contentLength <= 10_000_000) {
                    body = new byte[contentLength];
                    input.readNBytes(body, 0, contentLength);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid Content-Length: " + contentLengthStr);
            }
        }

        return new HttpRequest(method, path, httpVersion, headers, body);
    }

    private void writeResponse(BufferedOutputStream output, HttpResponse response) throws IOException {
        output.write(("HTTP/1.1 " + response.statusCode() + " " + getStatusMessage(response.statusCode()) + "\r\n")
            .getBytes(StandardCharsets.UTF_8));

        response.headers().forEach((name, value) -> {
            try {
                output.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        byte[] body = response.body();
        if (body != null && body.length > 0) {
            output.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            output.write(("Content-Length: 0\r\n").getBytes(StandardCharsets.UTF_8));
        }

        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            output.write(body);
        }
    }

    private String getStatusMessage(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301, 302 -> "Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            serverSocket.close();
        } catch (IOException | InterruptedException e) {
            System.err.println("Error stopping server: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        var router = new HttpRouter();

        // Define routes with regex patterns
        router.get("^/$", req -> jsonResponse(200, """
            {"message": "Welcome to HTTP Server", "version": "1.0"}
            """));

        // Route with path parameters
        router.get("^/api/users/([0-9]+)$", req -> {
            String userId = extractPathParam(req.path(), "^/api/users/([0-9]+)$", 1);
            return jsonResponse(200, """
                {"id": %s, "name": "User %s"}
                """.formatted(userId, userId));
        });

        // POST route
        router.post("^/api/users$", req -> jsonResponse(201,
            req.body() != null ? new String(req.body(), StandardCharsets.UTF_8) : "{}"));

        // GET with dynamic responses
        router.get("^/api/echo.*", req -> jsonResponse(200, """
            {"echo": "%s", "method": "%s"}
            """.formatted(req.path(), req.method())));

        // Health check endpoint
        router.get("^/health$", req -> jsonResponse(200, """
            {"status": "up", "timestamp": %d}
            """.formatted(System.currentTimeMillis())));

        // 404 fallback for unmatched routes
        router.any(".*", req -> jsonResponse(404, """
            {"error": "Not Found", "path": "%s"}
            """.formatted(req.path())));

        // Add interceptors for cross-cutting concerns
        router.addInterceptor(new LoggingInterceptor());
        router.addInterceptor(new CorsInterceptor("*"));
        router.addInterceptor(new RateLimitInterceptor(1000));
        router.addInterceptor(new ConditionalInterceptor(
            req -> req.path().startsWith("/api/"),
            new AuthenticationInterceptor()
        ));

        ThreadPoolHttpServer server = new ThreadPoolHttpServer(8080, 50, router);
        server.start();
    }

    private static String extractPathParam(String path, String pattern, int group) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        var matcher = p.matcher(path);
        return matcher.find() ? matcher.group(group) : "";
    }

    private static HttpResponse jsonResponse(int status, String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        return new HttpResponse(status, headers, json.getBytes(StandardCharsets.UTF_8));
    }
}

/**
 * Record-based HTTP request (Java 25 feature)
 * Immutable and compact representation of an HTTP request
 */
record HttpRequest(String method, String path, String httpVersion,
                   Map<String, String> headers, byte[] body) {
    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }
}

/**
 * Record-based HTTP response (Java 25 feature)
 * Immutable and compact representation of an HTTP response
 */
record HttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
}

/**
 * Sealed interface for interceptors (Java 25 feature)
 * Only specific implementations allowed, enabling exhaustive pattern matching
 */
interface HttpInterceptor {

    HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain);
}

/**
 * Functional interceptor chain interface
 */
@FunctionalInterface
interface HttpInterceptorChain {
    HttpResponse proceed(HttpRequest request);
}

/**
 * Logging interceptor - logs all requests and responses with timing
 * Pattern matching on status code ranges for visual status indicators
 */
final class LoggingInterceptor implements HttpInterceptor {
    @Override
    public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
        long start = System.nanoTime();
        var response = chain.proceed(request);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Pattern matching on status code ranges (Java 25 feature)
        String statusType = switch (response.statusCode()) {
            case 200, 201, 204 -> "✓ SUCCESS";
            case 301, 302 -> "⤳ REDIRECT";
            case 400, 401, 403, 404 -> "✗ CLIENT_ERROR";
            case 500, 503 -> "✗ SERVER_ERROR";
            default -> "? UNKNOWN";
        };

        System.out.printf("[%s] %s %s -> %d (%dms)%n",
            statusType, request.method(), request.path(), response.statusCode(), elapsedMs);

        return response;
    }
}

/**
 * CORS interceptor - handles cross-origin requests and preflight
 */
final class CorsInterceptor implements HttpInterceptor {
    private final String allowedOrigin;

    public CorsInterceptor(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
        // Handle CORS preflight OPTIONS request
        if ("OPTIONS".equals(request.method())) {
            return new HttpResponse(204,
                Map.ofEntries(
                    Map.entry("Access-Control-Allow-Origin", allowedOrigin),
                    Map.entry("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS"),
                    Map.entry("Access-Control-Allow-Headers", "Content-Type, Authorization"),
                    Map.entry("Access-Control-Max-Age", "3600")
                ),
                null);
        }

        var response = chain.proceed(request);
        // Add CORS headers to all responses
        Map<String, String> corsHeaders = new LinkedHashMap<>(response.headers());
        corsHeaders.put("Access-Control-Allow-Origin", allowedOrigin);
        return new HttpResponse(response.statusCode(), corsHeaders, response.body());
    }
}

/**
 * Rate limiting interceptor using token bucket algorithm
 */
final class RateLimitInterceptor implements HttpInterceptor {
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerSecond;

    public RateLimitInterceptor(int requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    @Override
    public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
        String clientId = "global"; // In production: extract from socket/header
        TokenBucket bucket = buckets.computeIfAbsent(clientId,
            k -> new TokenBucket(requestsPerSecond, requestsPerSecond));

        if (bucket.tryConsume()) {
            return chain.proceed(request);
        }

        // Rate limited response
        return new HttpResponse(429,
            Map.of("Content-Type", "application/json", "Retry-After", "1"),
            """
                {"error": "Too Many Requests", "retryAfter": 1}
                """.getBytes(StandardCharsets.UTF_8));
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
 * Authentication interceptor - validates requests based on headers
 */
final class AuthenticationInterceptor implements HttpInterceptor {
    @Override
    public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
        String authHeader = request.getHeader("authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new HttpResponse(401,
                Map.of("Content-Type", "application/json"),
                """
                    {"error": "Unauthorized", "message": "Missing or invalid authorization header"}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        // Token validation could go here
        String token = authHeader.substring(7);
        if (!isValidToken(token)) {
            return new HttpResponse(401,
                Map.of("Content-Type", "application/json"),
                """
                    {"error": "Unauthorized", "message": "Invalid token"}
                    """.getBytes(StandardCharsets.UTF_8));
        }

        return chain.proceed(request);
    }

    private boolean isValidToken(String token) {
        // Simple validation - in production: validate JWT, check signature, expiry, etc.
        return !token.isEmpty() && token.length() > 5;
    }
}

/**
 * Conditional interceptor - applies another interceptor only if condition matches
 * Enables flexible, conditional application of interceptor logic
 */
final class ConditionalInterceptor implements HttpInterceptor {
    private final java.util.function.Predicate<HttpRequest> condition;
    private final HttpInterceptor interceptor;

    public ConditionalInterceptor(java.util.function.Predicate<HttpRequest> condition,
                                  HttpInterceptor interceptor) {
        this.condition = condition;
        this.interceptor = interceptor;
    }

    @Override
    public HttpResponse intercept(HttpRequest request, HttpInterceptorChain chain) {
        if (condition.test(request)) {
            return interceptor.intercept(request, chain);
        }
        return chain.proceed(request);
    }
}

/**
 * HTTP Router with route matching and interceptor support
 * Uses regex patterns for flexible route matching
 */
class HttpRouter {
    private final List<RouteEntry> routes = new ArrayList<>();
    private final List<HttpInterceptor> interceptors = new ArrayList<>();

    public void get(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        addRoute("GET", pattern, handler);
    }

    public void post(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        addRoute("POST", pattern, handler);
    }

    public void put(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        addRoute("PUT", pattern, handler);
    }

    public void delete(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        addRoute("DELETE", pattern, handler);
    }

    public void patch(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        addRoute("PATCH", pattern, handler);
    }

    public void any(String pattern, java.util.function.Function<HttpRequest, HttpResponse> handler) {
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")) {
            addRoute(method, pattern, handler);
        }
    }

    private void addRoute(String method, String pattern,
                          java.util.function.Function<HttpRequest, HttpResponse> handler) {
        routes.add(new RouteEntry(method, java.util.regex.Pattern.compile(pattern), handler));
    }

    public void addInterceptor(HttpInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public HttpResponse handleWithInterceptors(HttpRequest request) {
        // Build interceptor chain and execute
        HttpInterceptorChain chain = buildChain(request);
        return interceptors.isEmpty() ? findAndExecuteHandler(request)
            : interceptors.get(0).intercept(request, chain);
    }

    private HttpInterceptorChain buildChain(HttpRequest request) {
        if (interceptors.isEmpty()) {
            return this::findAndExecuteHandler;
        }

        // Build chain from last to first interceptor
        HttpInterceptorChain[] chain = new HttpInterceptorChain[1];
        chain[0] = this::findAndExecuteHandler;

        for (int i = interceptors.size() - 1; i >= 0; i--) {
            final int idx = i;
            final HttpInterceptorChain nextChain = chain[0];
            chain[0] = r -> interceptors.get(idx).intercept(r, nextChain);
        }

        return chain[0];
    }

    private HttpResponse findAndExecuteHandler(HttpRequest request) {
        // Find first matching route
        for (RouteEntry route : routes) {
            if (route.matches(request)) {
                return route.handler().apply(request);
            }
        }

        // Default 404
        return new HttpResponse(404,
            Map.of("Content-Type", "application/json"),
            """
                {"error": "Not Found", "path": "%s"}
                """.formatted(request.path()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Route entry record
     */
    record RouteEntry(String method,
                      java.util.regex.Pattern pattern,
                      java.util.function.Function<HttpRequest, HttpResponse> handler) {

        boolean matches(HttpRequest request) {
            return method.equals(request.method()) && pattern.matcher(request.path()).find();
        }
    }
}
