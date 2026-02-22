package gt.app.simple;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple HTTP/1.1 server using Virtual Threads.
 *
 * Features:
 * - Listens on port 8081
 * - Handles concurrent requests with virtual threads
 * - Parses HTTP requests (method, path, headers)
 * - Sends proper HTTP responses
 * - Proper resource cleanup
 */
public class HttpServer {

    public static void main(String[] args) throws IOException {
        // Use virtual threads for excellent concurrency with minimal overhead
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try (ServerSocket serverSocket = new ServerSocket(8081)) {
            System.out.println("✓ Server started on http://localhost:8081");
            System.out.println("✓ Press Ctrl+C to stop\n");

            while (true) {
                try {
                    // Accept new client connection
                    Socket client = serverSocket.accept();

                    // Handle in virtual thread (non-blocking)
                    executor.submit(() -> handleClient(client));

                } catch (IOException e) {
                    System.err.println("✗ Error accepting connection: " + e.getMessage());
                }
            }
        } finally {
            executor.shutdown();
            System.out.println("Server stopped");
        }
    }

    /**
     * Handle a single client connection
     */
    private static void handleClient(Socket client) {
        try (client) {  // Auto-close socket when done
            // Get input stream
            BufferedReader input = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
            );

            // ═══════════════════════════════════════════════════════════
            // STEP 1: Parse HTTP Request Line
            // ═══════════════════════════════════════════════════════════
            String requestLine = input.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;  // No request
            }

            // Parse: "GET /path HTTP/1.1"
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "UNKNOWN";
            String path = parts.length > 1 ? parts[1] : "/";
            String httpVersion = parts.length > 2 ? parts[2] : "HTTP/1.1";

            System.out.println("→ " + method + " " + path + " (" + httpVersion + ")");

            // ═══════════════════════════════════════════════════════════
            // STEP 2: Parse HTTP Headers
            // ═══════════════════════════════════════════════════════════
            String header;
            String contentType = "";
            String contentLength = "";

            while ((header = input.readLine()) != null && !header.isEmpty()) {
                // Parse header: "Name: Value"
                int colonIndex = header.indexOf(':');
                if (colonIndex > 0) {
                    String headerName = header.substring(0, colonIndex).trim();
                    String headerValue = header.substring(colonIndex + 1).trim();

                    // Log interesting headers
                    if (headerName.equalsIgnoreCase("Content-Type")) {
                        contentType = headerValue;
                        System.out.println("  ├─ Content-Type: " + contentType);
                    } else if (headerName.equalsIgnoreCase("Content-Length")) {
                        contentLength = headerValue;
                        System.out.println("  ├─ Content-Length: " + contentLength);
                    } else if (headerName.equalsIgnoreCase("Host")) {
                        System.out.println("  ├─ Host: " + headerValue);
                    } else if (headerName.equalsIgnoreCase("User-Agent")) {
                        System.out.println("  └─ User-Agent: " + headerValue);
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 3: Generate Response
            // ═══════════════════════════════════════════════════════════
            String responseBody;
            int statusCode = 200;

            // Simple routing
            if (path.equals("/")) {
                responseBody = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Simple HTTP Server</title>
                        <style>
                            body { font-family: Arial; margin: 40px; }
                            h1 { color: #333; }
                            code { background: #f0f0f0; padding: 2px 5px; }
                        </style>
                    </head>
                    <body>
                        <h1>✓ Hello from my HTTP Server!</h1>
                        <p>Request method: <code>{method}</code></p>
                        <p>Request path: <code>{path}</code></p>
                        <p><a href="/hello">Click here</a> for greeting</p>
                    </body>
                    </html>
                    """.replace("{method}", method).replace("{path}", path);
            } else if (path.equals("/hello")) {
                responseBody = """
                    <!DOCTYPE html>
                    <html>
                    <body>
                        <h1>Hello! 👋</h1>
                        <p><a href="/">Back to home</a></p>
                    </body>
                    </html>
                    """;
            } else if (path.startsWith("/api/")) {
                // Simple JSON API
                responseBody = """
                    {
                      "status": "success",
                      "path": "%s",
                      "message": "This is a JSON response"
                    }
                    """.formatted(path);

                // Send as JSON
                sendResponse(client.getOutputStream(), 200, responseBody, "application/json");
                System.out.println("← 200 OK (JSON)\n");
                return;
            } else {
                statusCode = 404;
                responseBody = """
                    <!DOCTYPE html>
                    <html>
                    <body>
                        <h1>404 - Not Found</h1>
                        <p>Path <code>{path}</code> not found</p>
                        <p><a href="/">Back to home</a></p>
                    </body>
                    </html>
                    """.replace("{path}", path);
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 4: Send HTTP Response
            // ═══════════════════════════════════════════════════════════
            sendResponse(client.getOutputStream(), statusCode, responseBody, "text/html");

            System.out.println("← " + statusCode + " OK\n");

        } catch (IOException e) {
            System.err.println("✗ Error handling client: " + e.getMessage());
        }
    }

    /**
     * Send HTTP response
     */
    private static void sendResponse(OutputStream output, int statusCode,
                                     String body, String contentType) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // Build HTTP response
        String statusText = switch (statusCode) {
            case 200 -> "OK";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };

        String httpResponse = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: " + contentType + "; charset=UTF-8\r\n" +
            "Content-Length: " + bodyBytes.length + "\r\n" +
            "Connection: close\r\n" +  // Tell client to close connection
            "\r\n";  // Empty line separates headers from body

        // Write response
        output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
        output.write(bodyBytes);
        output.flush();
    }
}
