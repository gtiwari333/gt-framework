package gt.app;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class HttpServer {
    public static void main(String[] args) throws IOException {
        // 1. Listen on port 8080
        try (ServerSocket serverSocket = new ServerSocket(8081)) {
            System.out.println("Server started on http://localhost:8080");

            while (true) {
                // 2. Wait for a client connection
                try (Socket client = serverSocket.accept()) {
                    handleClient(client);
                }
            }
        }
    }

    private static void handleClient(Socket client) throws IOException {
        // 3. Read the request (optional for a simple "Hello" response)
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        String line = reader.readLine();
//        if (line != null) System.out.println("Request: " + line);

        // 4. Write the HTTP response
        OutputStream output = client.getOutputStream();
        String responseBody = "<h1>Hello from my scratch server!</h1>";

        // HTTP Response Format: Status Line -> Headers -> Empty Line -> Body
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: " + responseBody.length() + "\r\n" +
            "\r\n" +
            responseBody;

        output.write(httpResponse.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
