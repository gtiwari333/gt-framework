package gt.app.netty2;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Examples and usage patterns for Netty HTTP Server Framework
 */
public class NettyServerExamples {

    /**
     * Example 1: Basic Setup - Simple REST API
     */
    public static void basicRestAPI() throws Exception {
        var server = new NettyHttpServerFramework(8080);
        var router = server.getRouter();

        // Simple GET endpoint
        router.get("^/$", req -> jsonResponse(200, """
            {"message": "Welcome to Netty HTTP Server", "version": "1.0"}
            """));

        // GET with path parameter
        router.get("^/api/users/([0-9]+)$", req -> {
            String userId = extractPathParam(req.path(), "^/api/users/([0-9]+)$");
            return jsonResponse(200, """
                {"id": %s, "name": "User %s", "email": "user%s@example.com"}
                """.formatted(userId, userId, userId));
        });

        // POST endpoint
        router.post("^/api/users$", req -> jsonResponse(201, """
            {"created": true, "id": 123, "data": "User created successfully"}
            """));

        // Start server
        server.start();
    }

    /**
     * Example 2: File Upload Support
     */
    public static void fileUploadExample() throws Exception {
        var server = new NettyHttpServerFramework(8080);
        var router = server.getRouter();

        String uploadDir = "/tmp/uploads";
        new File(uploadDir).mkdirs();

        // File upload endpoint
        router.post("^/api/upload$", req -> {
            try {
                byte[] fileData = req.body();
                String filename = req.getHeader("x-filename");

                if (filename == null || filename.isEmpty()) {
                    return jsonResponse(400, """
                        {"error": "Missing x-filename header"}
                        """);
                }

                // Save file
                String filepath = uploadDir + "/" + filename;
                Files.write(Paths.get(filepath), fileData);

                return jsonResponse(200, """
                    {"uploaded": true, "filename": "%s", "size": %d}
                    """.formatted(filename, fileData.length));

            } catch (Exception e) {
                return jsonResponse(500, """
                    {"error": "%s"}
                    """.formatted(e.getMessage()));
            }
        });

        server.start();
    }

    /**
     * Example 4: With Interceptors - Logging, CORS, Rate Limiting
     */
    public static void withInterceptorsExample() throws Exception {
        var server = new NettyHttpServerFramework(8080);
        var router = server.getRouter();

        // Add interceptors
        router.addInterceptor(new LoggingInterceptor());
        router.addInterceptor(new CorsInterceptor("*"));
        router.addInterceptor(new RateLimitInterceptor(100)); // 100 requests/sec

        // Routes
        router.get("^/api/data$", req -> jsonResponse(200, """
            {
              "data": [1, 2, 3, 4, 5],
              "count": 5,
              "timestamp": %d
            }
            """.formatted(System.currentTimeMillis())));

        router.post("^/api/data$", req -> {
            String body = new String(req.body() != null ? req.body() : new byte[0],
                StandardCharsets.UTF_8);
            return jsonResponse(201, """
                {"received": true, "data": %s}
                """.formatted(body));
        });

        server.start();
    }

    /**
     * Example 5: Complete Application - REST API + Streaming + Uploads
     */
    public static void completeApplicationExample() throws Exception {
        var server = new NettyHttpServerFramework(8080);
        var router = server.getRouter();

        String uploadDir = "/tmp/uploads";
        String mediaDir = "/tmp/media";
        new File(uploadDir).mkdirs();
        new File(mediaDir).mkdirs();

        // Setup interceptors
//        router.addInterceptor(new LoggingInterceptor());
        router.addInterceptor(new CorsInterceptor("*"));
//        router.addInterceptor(new RateLimitInterceptor(200)); // 200 req/sec

        // ═══════════════════════════════════════════════════════════
        // REST API Endpoints
        // ═══════════════════════════════════════════════════════════

        router.get("^/$", req -> jsonResponse(200, """
            {
              "name": "Netty HTTP Server",
              "version": "1.0",
              "features": ["REST API", "Streaming", "File Upload"],
              "endpoints": {
                "GET /api/users": "List users",
                "GET /api/users/{id}": "Get user",
                "POST /api/users": "Create user",
                "POST /api/upload": "Upload file",
                "GET /stream/{filename}": "Stream media file"
              }
            }
            """));

        // User management
        router.get("^/api/users$", req -> jsonResponse(200, """
            {
              "users": [
                {"id": 1, "name": "Alice"},
                {"id": 2, "name": "Bob"},
                {"id": 3, "name": "Charlie"}
              ]
            }
            """));

        router.get("^/api/users/([0-9]+)$", req -> {
            String userId = extractPathParam(req.path(), "^/api/users/([0-9]+)$");
            return jsonResponse(200, """
                {"id": %s, "name": "User %s", "email": "user%s@example.com"}
                """.formatted(userId, userId, userId));
        });

        router.post("^/api/users$", req -> jsonResponse(201, """
            {"created": true, "id": 123}
            """));

        // ═══════════════════════════════════════════════════════════
        // File Upload
        // ═══════════════════════════════════════════════════════════

        router.post("^/api/upload$", req -> {
            try {
                byte[] fileData = req.body();
                String filename = req.getHeader("x-filename");

                if (filename == null) {
                    return jsonResponse(400, """
                        {"error": "Missing x-filename header"}
                        """);
                }

                Files.write(Paths.get(uploadDir + "/" + filename), fileData);

                return jsonResponse(200, """
                    {
                      "uploaded": true,
                      "filename": "%s",
                      "size": %d,
                      "message": "File uploaded successfully"
                    }
                    """.formatted(filename, fileData.length));

            } catch (Exception e) {
                return jsonResponse(500, """
                    {"error": "%s"}
                    """.formatted(e.getMessage()));
            }
        });

        // List uploaded files
        router.get("^/api/uploads$", req -> {
            try {
                File dir = new File(uploadDir);
                String[] files = dir.list();
                StringBuilder json = new StringBuilder("[");

                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        if (i > 0) json.append(",");
                        json.append("\"").append(files[i]).append("\"");
                    }
                }

                json.append("]");
                return jsonResponse(200, """
                    {"files": %s, "count": %d}
                    """.formatted(json, files != null ? files.length : 0));

            } catch (Exception e) {
                return jsonResponse(500, """
                    {"error": "%s"}
                    """.formatted(e.getMessage()));
            }
        });

        // Get media info
        router.get("^/api/media/(.+)$", req -> {
            String filename = extractPathParam(req.path(), "^/api/media/(.+)$");
            File file = new File(mediaDir + "/" + filename);

            if (!file.exists()) {
                return jsonResponse(404, """
                    {"error": "File not found"}
                    """);
            }

            return jsonResponse(200, """
                {
                  "filename": "%s",
                  "size": %d,
                  "type": "%s",
                  "lastModified": %d,
                  "streamUrl": "/stream/%s"
                }
                """.formatted(
                    filename,
                    file.length(),
                    getContentType(filename),
                    file.lastModified(),
                    filename
                ));
        });

        // ═══════════════════════════════════════════════════════════
        // Health Check
        // ═══════════════════════════════════════════════════════════

        router.get("^/health$", req -> jsonResponse(200, """
            {
              "status": "UP",
              "timestamp": %d,
              "uptime": "running"
            }
            """.formatted(System.currentTimeMillis())));

        System.out.println("""

            ╔══════════════════════════════════════════════════════════╗
            ║  Netty HTTP Server Framework - Complete Example         ║
            ╠══════════════════════════════════════════════════════════╣
            ║  ✓ Virtual threads enabled                              ║
            ║  ✓ Java 25 features (records, sealed, pattern match)   ║
            ║  ✓ Interceptors: Logging, CORS, Rate Limiting          ║
            ║  ✓ File upload support                                 ║
            ║  ✓ HTTP/1.1 compliant                                  ║
            ╠══════════════════════════════════════════════════════════╣
            ║  API Endpoints:                                          ║
            ║  GET  /                - API info                       ║
            ║  GET  /api/users       - List users                     ║
            ║  GET  /api/users/{id}  - Get user                       ║
            ║  POST /api/users       - Create user                    ║
            ║  POST /api/upload      - Upload file                    ║
            ║  GET  /api/uploads     - List uploads                   ║
            ║  GET  /api/media/{file} - Get media info                ║
            ║  GET  /health         - Health check                    ║
            ║                                                          ║
            ║  Test Examples:                                          ║
            ║  curl http://localhost:8080/                            ║
            ║  curl http://localhost:8080/api/users                   ║
            ║  curl http://localhost:8080/api/users/42                ║
            ║  curl -X POST http://localhost:8080/api/users           ║
            ╚══════════════════════════════════════════════════════════╝
            """);

        server.start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════

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

    private static String getContentType(String filename) {
        return switch (filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()) {
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mpeg" -> "video/mpeg";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    public static void main(String[] args) throws Exception {
        // Uncomment the example you want to run:

        // completeApplicationExample();  // ← Recommended: Full-featured demo
        // basicRestAPI();
        // fileUploadExample();
        // streamingMediaExample();
        // withInterceptorsExample();

        completeApplicationExample();
    }
}
