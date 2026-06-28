package ISCS;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ISCS {

    // A2 PREP: Store targets as Lists to handle multiple IPs later.
    // Logic: Map<"UserService", ["127.0.0.1:14001", "192.168.1.5:14001"]>
    private static final Map<String, List<String>> serviceRegistry = new HashMap<>();
    
    // Round-Robin Counters
    private static final Map<String, AtomicInteger> rrCounters = new HashMap<>();
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 8000;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java ISCS <config.json>");
            System.exit(1);
        }

        System.out.println(">>> Starting ISCS (Internal Router)...");
        loadConfiguration(args[0]);

        // 1. Get ISCS Configuration (Where should I listen?)
        if (!serviceRegistry.containsKey("InterServiceCommunication")) {
            throw new RuntimeException("Config missing 'InterServiceCommunication' block");
        }
        String iscsFullAddress = serviceRegistry.get("InterServiceCommunication").get(0); 
        int port = Integer.parseInt(iscsFullAddress.split(":")[1]);

        // 2. Start the Server
        // Maximum queued incoming connections to allow is set to 0 (system default)
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/shutdown", exchange -> {
            String method = exchange.getRequestMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }

            // stop after responding
            server.stop(0);
            System.exit(0);
        });

        // 3. Create Contexts
        // We catch ALL traffic and route it dynamically.
        server.createContext("/", new RouterHandler());

        // Use a thread pool to handle multiple requests (A2 scalability)
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(40));
        server.start();
        
        System.out.println(">>> ISCS Listening on port " + port);
        System.out.println(">>> Ready to route requests from Order Service.");
    }

    private static void loadConfiguration(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        JsonObject config = JsonParser.parseString(content).getAsJsonObject();

        // Register the services we need to talk to
        // Note: Even though A1 config has single objects, we store them as lists
        registerService(config, "UserService");
        registerService(config, "ProductService");
        
        // We also register OrderService just in case we need to route BACK to it 
        // or if A2 requires ISCS to sit in front of everything.
        registerService(config, "OrderService");
        
        // Register self to know port
        registerService(config, "InterServiceCommunication");
    }

    private static void registerService(JsonObject config, String serviceName) {
        if (!config.has(serviceName)) return;

        JsonObject serviceBlock = config.getAsJsonObject(serviceName);
        String ip = serviceBlock.get("ip").getAsString();
        int port = serviceBlock.get("port").getAsInt();
        String address = ip + ":" + port;

        // A2 SCALABILITY: Always treat as a list
        serviceRegistry.putIfAbsent(serviceName, new ArrayList<>());
        serviceRegistry.get(serviceName).add(address);
        
        // Initialize counter for this service
        rrCounters.putIfAbsent(serviceName, new AtomicInteger(0));
        
        System.out.println("   + Registered Node for " + serviceName + ": " + address);
    }

    /**
     * The Traffic Controller
     * Accepts request from OrderService -> Forwards to User/Product Service
     */
    static class RouterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath(); // e.g., "/user/get/1"
            String method = exchange.getRequestMethod();
            
            // --- 1. DETERMINE TARGET SERVICE ---
            String targetServiceName = null;
            
            if (path.startsWith("/user")) {
                targetServiceName = "UserService";
            } else if (path.startsWith("/product")) {
                targetServiceName = "ProductService";
            } else if (path.startsWith("/order")) {
                // If the OrderService accidentally calls itself via ISCS, or for A2 routing
                targetServiceName = "OrderService";
            } else {
                sendError(exchange, 404, "Unknown Route: " + path);
                return;
            }

            // --- 2. LOAD BALANCING (Round Robin) ---
            String targetAddress = getNextNode(targetServiceName);
            
            if (targetAddress == null) {
                sendError(exchange, 503, "Service Not Available: " + targetServiceName);
                return;
            }

            // --- 3. CONSTRUCT FORWARD REQUEST ---
            String targetUrl = "http://" + targetAddress + path;
            // Append query strings if they exist
            if (exchange.getRequestURI().getQuery() != null) {
                targetUrl += "?" + exchange.getRequestURI().getQuery();
            }

            System.out.println("[ISCS] Routing: " + method + " " + path + " -> " + targetUrl);

            // --- 4. EXECUTE FORWARDING ---
            HttpURLConnection connection = null;
            try {
                URL url = new URL(targetUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);

                // Forward Headers (skip specific ones that might cause issues)
                for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                    if (!header.getKey().equalsIgnoreCase("Host") && 
                        !header.getKey().equalsIgnoreCase("Content-Length")) {
                        for (String val : header.getValue()) {
                            connection.addRequestProperty(header.getKey(), val);
                        }
                    }
                }

                // Forward Body (if exists)
                if (allowsRequestBody(method)) {
                    byte[] requestBody = readAll(exchange.getRequestBody());
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(requestBody.length);
                    try (OutputStream targetBody = connection.getOutputStream()) {
                        targetBody.write(requestBody);
                    }
                }

                // --- 5. RECEIVE RESPONSE ---
                int responseCode = connection.getResponseCode();
                
                // Read response body (handle error streams correctly)
                InputStream targetResponseStream = (responseCode >= 400) 
                    ? connection.getErrorStream() 
                    : connection.getInputStream();

                byte[] responseBytes;
                if (targetResponseStream != null) {
                    responseBytes = readAll(targetResponseStream);
                } else {
                    responseBytes = new byte[0];
                }

                // --- 6. SEND BACK TO ORIGIN (Order Service) ---
                String contentType = connection.getHeaderField("Content-Type");
                exchange.getResponseHeaders().set(
                        "Content-Type",
                        contentType == null ? "application/json; charset=utf-8" : contentType
                );
                exchange.sendResponseHeaders(responseCode, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "ISCS Forwarding Error: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    // --- Helpers ---

    private static String getNextNode(String serviceName) {
        List<String> nodes = serviceRegistry.get(serviceName);
        if (nodes == null || nodes.isEmpty()) return null;

        AtomicInteger counter = rrCounters.get(serviceName);
        int index = Math.floorMod(counter.getAndIncrement(), nodes.size());
        
        return nodes.get(index);
    }

    private static boolean allowsRequestBody(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyStream(input, output);
        return output.toByteArray();
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = "{\"error\": \"" + message + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
