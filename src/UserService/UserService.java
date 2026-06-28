package UserService;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class UserService {

    private static final Gson GSON = new Gson();
    private static HttpServer server;

    public static void main(String[] args) throws IOException {
        // 1. Initialize DB before server starts
        UserDatabaseManager.initialize();

        int port = 8081; // default port
        String ip = "127.0.0.1"; // default IP

        // Load config from file if provided
        if (args.length > 0) {
            try {
                String configContent = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), StandardCharsets.UTF_8);
                com.google.gson.JsonObject config = GSON.fromJson(configContent, com.google.gson.JsonObject.class);
                com.google.gson.JsonObject userConfig = config.getAsJsonObject("UserService");
                if (userConfig != null) {
                    if (userConfig.has("port")) {
                        port = userConfig.get("port").getAsInt();
                    }
                    if (userConfig.has("ip")) {
                        ip = userConfig.get("ip").getAsString();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to load config: " + e.getMessage());
                System.err.println("Using default port " + port);
            }
        }

        server = HttpServer.create(new InetSocketAddress(ip, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(20));

        server.createContext("/user", new UserHandler());

        server.start();
        System.out.println("Server started on " + ip + ":" + port);
    }

    // --- HANDLER FOR /user (Handles both POST and GET) ---
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/user/reset".equals(path)) {
                UserDatabaseManager.resetDatabase();   // implement this in UserDatabaseManager
                sendResponse(exchange, 200, "{}");
                return;
            }
            if ("/user/shutdown".equals(path)) {
                sendResponse(exchange, 200, "{}");
                try { if (server != null) server.stop(0); } catch (Exception ignored) {}
                System.exit(0);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            
            if ("POST".equals(method)) {
                handlePost(exchange);
            } else if ("GET".equals(method)) {
                handleGet(exchange);
            } else {
                sendResponse(exchange, 405, "{}"); // method not allowed
            }
        }
        
        private void handlePost(HttpExchange exchange) throws IOException {
            try {
                // Read and Parse JSON
                String body = getRequestBody(exchange);
                UserData user = GSON.fromJson(body, UserData.class);
                if (user == null) {
                        sendResponse(exchange, 400, "{}"); // empty request body
                        return;
                    }
                if (user.command == null) {
                    sendResponse(exchange, 400, "{}"); // command required
                    return;
                }
                // Execute Logic based on Command
                switch (user.command.toLowerCase()) {
                    case "create":
                        // NEED TO MAKE ID A PART OF THE SCHEMA
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "{}"); // id required
                            return;
                        }
                        if (user.username == null || user.username.isEmpty()) {
                            sendResponse(exchange, 400, "{}"); // username required
                            return;
                        }
                        if (user.password == null || user.password.isEmpty()) {
                            sendResponse(exchange, 400, "{}"); // password required
                            return;
                        }
                        if (user.email == null || !isValidEmail(user.email)) {
                            sendResponse(exchange, 400, "{}"); // invalid or missing email
                            return;
                        }
                        
                        // Hash password only after validation passes
                        String hashedPassword = hashPassword(user.password);
                        user.password = hashedPassword;
                        UserDatabaseManager.createUser(user.id, user.username, user.email, user.password);
                        // CHANGE: Return the user object as JSON instead of a text string
                        user.command = null;
                        String jsonResponse = GSON.toJson(user); 
                        sendResponse(exchange, 200, jsonResponse);
                        break;
                        
                    case "update":
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "{}"); // id required
                            return;
                        }

                        // 1. Fetch the CURRENT database state
                        UserData existingUser = convertToUserData(UserDatabaseManager.getUser(user.id));
                        if (existingUser == null) {
                            sendResponse(exchange, 404, "{}"); // user id not found
                            return;
                        }

                        // 2. Merge Email (Update only if valid and present)
                        if (user.email != null) {
                                if (user.email.isEmpty()) {
                                    sendResponse(exchange, 400, "{}"); // email empty
                                    return;
                                }
                                if (!isValidEmail(user.email)) {
                                    sendResponse(exchange, 400, "{}"); // invalid email format
                                    return;
                                }
                                existingUser.email = user.email;
                        }

                        // 3. Merge Username (Update only if present)
                        if (user.username != null) {
                                if (user.username.isEmpty()) {
                                    sendResponse(exchange, 400, "{}"); // username cannot be empty
                                    return;
                                }
                                existingUser.username = user.username;
                        }

                        // 4. Merge Password (Update and HASH if present)
                        if (user.password != null) {
                                if (user.password.isEmpty()) {
                                    sendResponse(exchange, 400, "{}"); // password cannot be empty
                                    return;
                                }
                                existingUser.password = hashPassword(user.password);
                        }

                        // 5. Save to Database
                        // Note: ensure UserDatabaseManager.updateUser is updated to accept 4 arguments
                        UserDatabaseManager.updateUser(existingUser.id, existingUser.username, existingUser.email, existingUser.password);

                        // 6. Return the MERGED object so the client sees the full updated state
                        sendResponse(exchange, 200, GSON.toJson(existingUser));
                        break;
                        
                    case "delete":
                        if (user.id == 0) {
                            sendResponse(exchange, 400, "{}"); // id required for delete
                            return;
                        }
                        if (user.password == null) {
                            sendResponse(exchange, 400, "{}"); // password required for verification
                            return;
                        }
                        if (user.username == null || user.username.isEmpty()) {
                            sendResponse(exchange, 400, "{}"); // username is required
                            return;
                        }
                        if (user.email == null || !isValidEmail(user.email)) {
                            sendResponse(exchange, 400, "{}"); // invalid or missing email
                            return;
                        }
                        UserData dbUser = convertToUserData(UserDatabaseManager.getUser(user.id));
                        if (dbUser == null) {
                            sendResponse(exchange, 404, "{}"); // user not found
                            return;
                        }
                        String inputHash = hashPassword(user.password);
                        boolean usernameMatch = dbUser.username.equals(user.username);
                        boolean emailMatch = dbUser.email.equals(user.email);
                        boolean passMatch = dbUser.password.equals(inputHash);
                        if (usernameMatch && emailMatch && passMatch) {
                            UserDatabaseManager.deleteUser(user.id);
                            sendResponse(exchange, 200, "{}");
                        } else {
                            sendResponse(exchange, 401, "{}"); // data mismatch
                        }
                        break;
                        
                    default:
                        sendResponse(exchange, 400, "{}"); // unknown command
                }
            } catch (SQLException e) {
                // SQLite throws specific messages for constraint violations
                if (isUniqueConstraint(e)) {
                    sendResponse(exchange, 409, "{}"); // duplicate user
                } else {
                    e.printStackTrace();
                    sendResponse(exchange, 500, "Database Error: " + e.getMessage());
                }
            } catch (JsonSyntaxException e) {
                // Invalid JSON format
                sendResponse(exchange, 400, "{}"); // invalid json format
            } catch (Exception e) {
                e.printStackTrace(); // Log to console for debugging
                sendResponse(exchange, 500, "Server Error: " + e.getMessage());
            }
        }
        
        private void handleGet(HttpExchange exchange) throws IOException {
            // Parse ID from URL: /user/1001
            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");
            
            // Expecting segments like ["", "user", "1001"]
            if (segments.length < 3) { 
                sendResponse(exchange, 400, "{}"); // invalid url path
                return;
            }
            
            try {
                int id = Integer.parseInt(segments[segments.length - 1]);
                
                // 2. Fetch User
                UserData user = convertToUserData(UserDatabaseManager.getUser(id));

                if (user != null) {
                    String jsonResponse = GSON.toJson(user);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    sendResponse(exchange, 200, jsonResponse);
                } else {
                    sendResponse(exchange, 404, "{}"); // user not found
                }
                
            } catch (NumberFormatException e) {
                // 3. Handle non-integer IDs (e.g., "id=abcd")
                sendResponse(exchange, 400, "{}"); // id must be a number
            }
        }
    }

    // --- HELPER METHODS ---

    private static UserData convertToUserData(UserDatabaseManager.UserData dbUser) {
        if (dbUser == null) return null;
        UserData user = new UserData();
        user.id = dbUser.id;
        user.username = dbUser.username;
        user.email = dbUser.email;
        user.password = dbUser.password;
        user.command = null; // command is not in DatabaseManager.UserData
        return user;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response ) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder requestBody = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                requestBody.append(line);
            }
            return requestBody.toString();
        }
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            
            // Convert byte array to Hex String
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase(); // To match your uppercase output
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern VALID_EMAIL_ADDRESS_REGEX = 
        Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", Pattern.CASE_INSENSITIVE);

    public static boolean isValidEmail(String emailStr) {
        Matcher matcher = VALID_EMAIL_ADDRESS_REGEX.matcher(emailStr);
        return matcher.matches();
    }

    private static boolean isUniqueConstraint(SQLException e) {
        String state = e.getSQLState();
        String message = e.getMessage();
        return "23505".equals(state)
                || e.getErrorCode() == 19
                || (message != null && (
                        message.toLowerCase().contains("duplicate key")
                                || message.toLowerCase().contains("unique constraint")
                ));
    }

    // --- USER DATA CLASS ---
    public static class UserData {
        public int id;
        public String username;
        public String email;
        public String password;
        public String command;
    }
}
