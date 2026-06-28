package OrderService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;  // add at top


public class OrderService {

    private static final Gson GSON = new Gson();
    private static HttpServer server;
    private static int port;
    private static String iscsBase;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final AtomicBoolean firstGateDone = new AtomicBoolean(false);

    // In-memory order store (allowed unless spec says otherwise)
    private static final Map<String, JsonObject> orders = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java OrderService <config.json>");
            System.exit(1);
        }

        // Initialize database before server starts
        OrderDatabaseManager.initialize();

        loadConfig(args[0]);

        server = HttpServer.create(new InetSocketAddress(port), 0); // CHANGED
        server.createContext("/user/purchased", new UserPurchasesHandler());
        server.createContext("/user", new ProxyHandler());
        server.createContext("/product", new ProxyHandler());
        server.createContext("/order", new OrderHandler());

        server.setExecutor(Executors.newFixedThreadPool(40));
        server.start();

        System.out.println("OrderService running on port " + port);
    }

    private static void loadConfig(String path) throws IOException {
        String json = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        JsonObject cfg = JsonParser.parseString(json).getAsJsonObject();

        JsonObject os = cfg.getAsJsonObject("OrderService");
        port = os.get("port").getAsInt();

        JsonObject iscs = cfg.getAsJsonObject("InterServiceCommunication");
        iscsBase = "http://" + iscs.get("ip").getAsString() + ":" + iscs.get("port").getAsInt();
    }

    /* ===================== PROXY HANDLER ===================== */

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();

            String target = iscsBase + path + (query != null ? "?" + query : "");
            byte[] body = readAll(ex.getRequestBody());

            handleFirstRequestGate(body);

            HttpResult res = forward(method, target, body, ex);
            sendRaw(ex, res.code, res.body);
        }
    }

    /* ===================== ORDER HANDLER ===================== */

    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method)) {
                handleGet(ex, path);
                return;
            }

            if ("POST".equals(method) && "/order".equals(path)) {
                handlePost(ex);
                return;
            }

            sendError(ex, 404, "Invalid endpoint");
        }

        private void handleGet(HttpExchange ex, String path) throws IOException {
            String[] parts = path.split("/");
            if (parts.length != 3) {
                sendError(ex, 404, "Order id missing");
                return;
            }

            JsonObject order = orders.get(parts[2]);
            if (order == null) {
                sendError(ex, 404, "Order not found");
                return;
            }

            sendJson(ex, 200, order);
        }

        private void handlePost(HttpExchange ex) throws IOException {
            byte[] body = readAll(ex.getRequestBody());
            handleFirstRequestGate(body);

            JsonObject req;
            try {
                req = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // Handle Restart (must be before "place order only" check)
            if (req.has("command") && "restart".equalsIgnoreCase(req.get("command").getAsString())) {
                // First-request behavior (wipe vs keep) already handled by handleFirstRequestGate(body).
                // Here we just ACK so WorkloadParser doesn't see a 400 and "skip over it".
                respondStatus(ex, 200, "Restart acknowledged");
                return;
            }

            // NEW: accept shutdown command (before the "place order" validation)
            try {
                if (req.has("command") && "shutdown".equalsIgnoreCase(req.get("command").getAsString())) {
                    respondStatus(ex, 200, "Shutting down");

                    // tell other services to stop (via ISCS routing)
                    requestShutdown(iscsBase + "/user/shutdown");
                    requestShutdown(iscsBase + "/product/shutdown");
                    requestShutdown(iscsBase + "/shutdown"); // ISCS itself (we'll add this endpoint)

                    // stop OrderService last
                    shutdownSelf();
                    return;
                }
            } catch (Exception ignored) {
                // fall through to normal validation
            }

            // Command check (treat wrong/missing command as invalid request for the tests)
            try {
                if (!req.has("command") ||
                        !"place order".equalsIgnoreCase(req.get("command").getAsString())) {
                    respondStatus(ex, 400, "Invalid Request");
                    return;
                }
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // Required fields + type checks (avoid throwing -> RemoteDisconnected)
            if (!req.has("user_id") || !req.has("product_id") || !req.has("quantity")) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            int userId, productId, qty;
            try {
                userId = req.get("user_id").getAsInt();
                productId = req.get("product_id").getAsInt();
                qty = req.get("quantity").getAsInt();
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            if (qty <= 0) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // 1) Check user exists (via ISCS)
            HttpResult userRes = forward("GET", iscsBase + "/user/" + userId, null, ex);
            if (userRes.code != 200) {
                // Test expects 404 for invalid user id
                respondStatus(ex, 404, "Invalid Request");
                return;
            }

            // 2) Get product (via ISCS)
            HttpResult prodRes = forward("GET", iscsBase + "/product/" + productId, null, ex);
            if (prodRes.code != 200) {
                // Test expects 404 for invalid product id
                respondStatus(ex, 404, "Invalid Request");
                return;
            }

            JsonObject product;
            try {
                product = JsonParser
                        .parseString(new String(prodRes.body, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            int stock;
            try {
                stock = product.get("quantity").getAsInt();
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // Special case: exceeded stock
            if (qty > stock) {
                respondStatus(ex, 400, "Exceeded quantity limit"); // (409 also acceptable per your spec)
                return;
            }

            // 3) Update product quantity
            JsonObject update = new JsonObject();
            update.addProperty("command", "update");
            update.addProperty("id", productId);
            update.addProperty("quantity", stock - qty);

            HttpResult updRes = forward(
                    "POST",
                    iscsBase + "/product",
                    GSON.toJson(update).getBytes(StandardCharsets.UTF_8),
                    ex
            );

            if (updRes.code != 200) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // 4) Record purchase in database
            try {
                OrderDatabaseManager.addOrUpdatePurchase(userId, productId, qty);
            } catch (Exception e) {
                respondStatus(ex, 400, "Invalid Request");
                return;
            }

            // 5) Store order internally (optional; not required by the create tests)
            String orderId = UUID.randomUUID().toString();
            JsonObject order = new JsonObject();
            order.addProperty("id", orderId);
            order.addProperty("user_id", userId);
            order.addProperty("product_id", productId);
            order.addProperty("quantity", qty);
            orders.put(orderId, order);

            // 6) Success response must include these keys (per your test output)
            respondSuccess(ex, userId, productId, qty);
        }

    }

    /* ===================== USER PURCHASES HANDLER ===================== */

    static class UserPurchasesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (!"GET".equals(method)) {
                sendError(ex, 405, "Method not allowed");
                return;
            }

            // Parse /user/purchased/{userId}
            String[] parts = path.split("/");
            if (parts.length != 4 || !"purchased".equals(parts[2])) {
                sendError(ex, 404, "Invalid endpoint");
                return;
            }

            int userId;
            try {
                userId = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                sendError(ex, 404, "Invalid user id");
                return;
            }

            // 1) Verify user exists via UserService (via ISCS)
            // might need to test
            HttpResult userRes = forward("GET", iscsBase + "/user/" + userId, null, ex);
            if (userRes.code != 200) {
                sendError(ex, 404, "User not found");
                return;
            }

            // 2) Get user's purchases from database
            Map<Integer, Integer> purchases = OrderDatabaseManager.getPurchasesForUser(userId);

            // 3) Build response JSON
            JsonObject response = new JsonObject();
            for (Map.Entry<Integer, Integer> entry : purchases.entrySet()) {
                response.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }

            // 4) Return response (200 with JSON, empty or populated)
            sendJson(ex, 200, response);
        }
    }

    /* ===================== HELPERS ===================== */

    private static void respondStatus(HttpExchange ex, int code, String status) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("status", status);
        byte[] b = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void respondSuccess(HttpExchange ex, int userId, int productId, int qty) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("product_id", productId);
        o.addProperty("user_id", userId);
        o.addProperty("quantity", qty);
        o.addProperty("status", "Success");

        byte[] b = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static class HttpResult {
        int code;
        byte[] body;
        HttpResult(int c, byte[] b) { code = c; body = b; }
    }

    private static HttpResult forward(String method, String urlStr, byte[] body, HttpExchange ex)
            throws IOException {

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");

            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct != null) conn.setRequestProperty("Content-Type", ct);

            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                byte[] payload = body == null ? new byte[0] : body;
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] resp = (is == null) ? new byte[0] : readAll(is);

            return new HttpResult(code, resp);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void sendJson(HttpExchange ex, int code, JsonObject obj) throws IOException {
        byte[] b = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendRaw(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        sendJson(ex, code, o);
    }

    private static void requestShutdown(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(new byte[0]);
            conn.getResponseCode(); // trigger request
            conn.disconnect();
        } catch (Exception ignored) {
            // best-effort shutdown
        }
    }

    private static void shutdownSelf() {
        try {
            if (server != null) server.stop(0);
        } catch (Exception ignored) {}
        System.exit(0);
    }

    private static void handleFirstRequestGate(byte[] body) {
        if (!firstGateDone.compareAndSet(false, true)) return;

        boolean isRestart = false;
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (json.has("command") && "restart".equalsIgnoreCase(json.get("command").getAsString())) {
                isRestart = true;
            }
        } catch (Exception ignored) {
            // Non-JSON / empty => isRestart stays false
        }

        if (!isRestart) {
            System.out.println("First request is not restart. Wiping databases...");
            wipeDatabases(); // <- your method
        } else {
            System.out.println("Restart detected. Keeping databases.");
        }
    }

    private static void wipeDatabases() {
        try {
            // Order DB
            OrderDatabaseManager.resetDatabase();   // or clear tables method

            // Tell other services to wipe themselves
            requestShutdown(iscsBase + "/user/reset");
            requestShutdown(iscsBase + "/product/reset");

        } catch (Exception e) {
            System.err.println("Failed to wipe databases: " + e.getMessage());
        }
    }
}

