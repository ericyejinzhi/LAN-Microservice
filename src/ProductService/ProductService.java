package ProductService;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.Executors;

public class ProductService {

    // IMPORTANT: UserService.java uses 8081. Avoid collision.
    private static final int PORT = 8082;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private static HttpServer server; // NEW

    // ---------- Model ----------
    public static class Product {
        int id;
        String name;        // <-- renamed
        String description;
        double price;
        int quantity;

        Product(int id, String name, String description, double price, int quantity) {
            this.id = id;
            this.name = (name == null) ? "" : name;
            this.description = (description == null) ? "" : description;
            this.price = price;
            this.quantity = quantity;
        }

        String toJson() {
            return "{\n" +
                    "    \"id\": " + id + ",\n" +
                    "    \"name\": \"" + escape(name) + "\",\n" +          // <-- output name
                    "    \"description\": \"" + escape(description) + "\",\n" +
                    "    \"price\": " + price + " ,\n" +
                    "    \"quantity\": " + quantity + "\n" +
                    "}";
        }

    private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ---- validation helpers ----
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void requireNonNull(HttpExchange ex, Object value, String field) throws IOException {
        if (value == null) {
            sendJson(ex, 400, "{\"error\":\"Missing or invalid required field: " + field + "\"}");
            throw new IllegalArgumentException("Missing/invalid " + field);
        }
    }

    private static void requireNonBlank(HttpExchange ex, String value, String field) throws IOException {
        if (isBlank(value)) {
            sendJson(ex, 400, "{\"error\":\"Field cannot be empty: " + field + "\"}");
            throw new IllegalArgumentException("Empty " + field);
        }
    }

    private static void requirePositiveInt(HttpExchange ex, Integer value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value <= 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be > 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireNonNegativeInt(HttpExchange ex, Integer value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value < 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireNonNegativeDouble(HttpExchange ex, Double value, String field) throws IOException {
        requireNonNull(ex, value, field);
        if (value.isNaN() || value.isInfinite() || value < 0.0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    // For PATCH-like update: validate only if provided
    private static void validateOptionalNonBlank(HttpExchange ex, String value, String field) throws IOException {
        if (value != null && isBlank(value)) {
            sendJson(ex, 400, "{\"error\":\"Field must not be blank: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void validateOptionalNonNegativeInt(HttpExchange ex, Integer value, String field) throws IOException {
        if (value != null && value < 0) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void validateOptionalNonNegativeDouble(HttpExchange ex, Double value, String field) throws IOException {
        if (value != null && (value.isNaN() || value.isInfinite() || value < 0.0)) {
            sendJson(ex, 400, "{\"error\":\"Field must be >= 0: " + field + "\"}");
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) throws IOException {
        ProductDatabaseManager.initialize();

        int port = PORT; // default port (8082)
        String ip = "127.0.0.1"; // default IP

        // Load config from file if provided
        if (args.length > 0) {
            try {
                String configContent = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), StandardCharsets.UTF_8);
                com.google.gson.JsonObject config = GSON.fromJson(configContent, com.google.gson.JsonObject.class);
                com.google.gson.JsonObject productConfig = config.getAsJsonObject("ProductService");
                if (productConfig != null) {
                    if (productConfig.has("port")) {
                        port = productConfig.get("port").getAsInt();
                    }
                    if (productConfig.has("ip")) {
                        ip = productConfig.get("ip").getAsString();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to load config: " + e.getMessage());
                System.err.println("Using default port " + port);
            }
        }

        server = HttpServer.create(new InetSocketAddress(ip, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.createContext("/product", new ProductHandler());
        server.start();

        System.out.println("ProductService started on " + ip + ":" + port);
        System.out.println("SQLite DB: products.db");
    }

    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/product/reset".equals(path)) {
                ProductDatabaseManager.resetDatabase();
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
                return;
            }
            if ("/product/shutdown".equals(path)) {
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
                try { if (server != null) server.stop(0); } catch (Exception ignored) {}
                System.exit(0);
                return;
            }
            try {
                String method = exchange.getRequestMethod();

                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                } else if ("GET".equalsIgnoreCase(method)) {
                    handleGet(exchange);
                } else {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.close();
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        }

        // -------- POST /product --------
        private void handlePost(HttpExchange exchange) throws IOException {
            try {
                String body = readBody(exchange);
                JsonObject json = parseJsonObject(exchange, body);

                String command = jString(json, "command");
                Integer id = jIntStrict(json, "id");  // MUST be integer

                requireNonBlank(exchange, command, "command");
                requirePositiveInt(exchange, id, "id");

                command = command.trim().toLowerCase();

                switch (command) {
                    case "create": handleCreate(exchange, json, id); break;
                    case "update": handleUpdate(exchange, json, id); break;
                    case "delete": handleDelete(exchange, json, id); break;
                    default: sendJson(exchange, 400, "{\"error\":\"Invalid command\"}");
                }
            } catch (IllegalArgumentException ignored) {
                // validation already responded with 400
            }
        }

        private void handleCreate(HttpExchange exchange, JsonObject json, int id) throws IOException {
            String name = jString(json, "name");
            String description = jString(json, "description"); // REQUIRED; cannot be blank
            Double price = jDouble(json, "price");
            Integer quantity = jIntStrict(json, "quantity");   // MUST be integer

            try {
                requireNonBlank(exchange, name, "name");
                requireNonBlank(exchange, description, "description");
                requireNonNegativeDouble(exchange, price, "price");
                requireNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }

            Product p = new Product(id, name, description, price, quantity);

            try {
                boolean created = ProductDatabaseManager.createProduct(p);
                if (!created) {
                    sendJson(exchange, 409, "{\"error\":\"Product id already exists\"}");
                    return;
                }
            } catch (SQLException e) {
                sendJson(exchange, 500, "{\"error\":\"Database error\"}");
                return;
            }

            sendJson(exchange, 200, p.toJson());
        }

        private void handleUpdate(HttpExchange exchange, JsonObject json, int id) throws IOException {
            String name = jString(json, "name");     // optional
            String description = jString(json, "description");     // optional; if provided cannot be blank
            Double price = jDouble(json, "price");                 // optional
            Integer quantity = jIntStrict(json, "quantity");       // optional; if present MUST be integer

            try {
                if (name == null && description == null && price == null && quantity == null) {
                    sendJson(exchange, 400, "{\"error\":\"No updatable fields provided\"}");
                    return;
                }

                if (name != null && isBlank(name)) {
                    sendJson(exchange, 400, "{\"error\":\"Field cannot be empty: name\"}");
                    return;
                }

                validateOptionalNonBlank(exchange, description, "description");
                validateOptionalNonNegativeDouble(exchange, price, "price");
                validateOptionalNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }

            try {
                Product existing = ProductDatabaseManager.getProduct(id);
                if (existing == null) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }

                if (name != null) existing.name = name;
                if (description != null) existing.description = description; // guaranteed nonblank
                if (price != null) existing.price = price;
                if (quantity != null) existing.quantity = quantity;

                boolean updated = ProductDatabaseManager.updateProduct(existing);
                if (!updated) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }

                sendJson(exchange, 200, existing.toJson());
            } catch (SQLException e) {
                sendJson(exchange, 500, "{\"error\":\"Database error\"}");
            }
        }

        private void handleDelete(HttpExchange exchange, JsonObject json, int id) throws IOException {
            // NOTE: description is intentionally NOT required for deletion
            String name = jName(json);
            Double price = jDouble(json, "price");
            Integer quantity = jIntStrict(json, "quantity"); // MUST be integer

            try {
                requireNonBlank(exchange, name, "name");
                requireNonNegativeDouble(exchange, price, "price");
                requireNonNegativeInt(exchange, quantity, "quantity");
            } catch (IllegalArgumentException ignored) {
                return;
            }

            try {
                ProductDatabaseManager.DeleteResult res =
                        ProductDatabaseManager.deleteProduct(id, name, price, quantity);

                if (res == ProductDatabaseManager.DeleteResult.NOT_FOUND) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }
                if (res == ProductDatabaseManager.DeleteResult.MISMATCH) {
                    sendJson(exchange, 401, "{\"error\":\"Delete failed: fields do not match\"}");
                    return;
                }

                sendJson(exchange, 200, "{\"status\":\"deleted\"}");
            } catch (SQLException e) {
                sendJson(exchange, 500, "{\"error\":\"Database error\"}");
            }
        }

        // -------- GET /product/<id> --------
        private void handleGet(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/product") || path.equals("/product/")) {
                sendJson(exchange, 400, "{\"error\":\"Missing product id\"}");
                return;
            }

            String prefix = "/product/";
            if (!path.startsWith(prefix)) {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }

            String idStr = path.substring(prefix.length());
            int slash = idStr.indexOf('/');
            if (slash >= 0) idStr = idStr.substring(0, slash);

            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, "{\"error\":\"Invalid product id\"}");
                return;
            }

            try {
                Product p = ProductDatabaseManager.getProduct(id);
                if (p == null) {
                    sendJson(exchange, 404, "{\"error\":\"Product not found\"}");
                    return;
                }
                sendJson(exchange, 200, p.toJson());
            } catch (SQLException e) {
                sendJson(exchange, 500, "{\"error\":\"Database error\"}");
            }
        }

        private String readBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }
    }

    // ---------- HTTP response helper ----------
    private static void sendJson(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Parse once per request body
    private static JsonObject parseJsonObject(HttpExchange ex, String body) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid JSON\"}");
            throw new IllegalArgumentException("invalid json");
        }
    }

    // Safe getters: return null if missing or explicit null
    private static String jString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsString(); } catch (Exception ignore) { return null; }
    }

    /**
     * Strict integer parsing:
     * - returns null if missing/null
     * - returns null if present but not an integer (e.g., 1.2, "5", "abc")
     */
    private static Integer jIntStrict(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;

        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;

        try {
            BigDecimal bd = e.getAsBigDecimal();
            bd = bd.stripTrailingZeros();
            if (bd.scale() > 0) return null; // fractional
            if (bd.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) return null;
            if (bd.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
            return bd.intValueExact();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Double jDouble(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsDouble(); } catch (Exception ignore) { return null; }
    }

    private static String jName(JsonObject o) {
        String n = jString(o, "name");
        if (n != null) return n;
        return jString(o, "productname"); // backward-compatible fallback
    }

}
