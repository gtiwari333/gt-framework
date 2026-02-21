package gt.app;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class RequestParserServer {
    public static void main(String[] args) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(8081)) {
            System.out.println("Server started at http://localhost:8080");
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    handleClient(client);
                }
            }
        }
    }

    private static void handleClient(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        String requestLine = reader.readLine(); // "GET /persons?age=1 HTTP/1.1"
        if (requestLine == null) return;

        // 1. Split the request line by spaces [METHOD, FULL_PATH, PROTOCOL]
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String fullPath = parts[1];

        // 2. Separate Path from Query String
        String path = fullPath;
        Map<String, String> queryParams = new HashMap<>();

        if (fullPath.contains("?")) {
            String[] pathParts = fullPath.split("\\?", 2);
            path = pathParts[0];

            // 3. Parse Query Parameters (key=value&key2=value2)
            String[] pairs = pathParts[1].split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                String key = keyValue[0];
                String value = keyValue.length > 1 ? keyValue[1] : "";
                queryParams.put(key, value);
            }
        }

        // Output results to console
        System.out.println("Method: " + method);
        System.out.println("Path: " + path);
        System.out.println("Params: " + queryParams);

        // 4. Send Response
        String responseBody = "Path: " + path + " | Age: " + queryParams.getOrDefault("age", "Unknown");
        sendResponse(client, responseBody);
    }

    private static void sendResponse(Socket client, String body) throws IOException {
        OutputStream out = client.getOutputStream();
        String response = "HTTP/1.1 200 OK\r\n" +
                          "Content-Type: text/plain\r\n" +
                          "Content-Length: " + body.length() + "\r\n" +
                          "\r\n" + body;
        out.write(response.getBytes());
        out.flush();
    }
}
