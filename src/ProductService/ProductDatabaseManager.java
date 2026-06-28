package ProductService;

import java.sql.*;

public class ProductDatabaseManager {

    private static final String DB_URL  = System.getenv("DB_URL")  != null ? System.getenv("DB_URL")  : "jdbc:sqlite:products.db";
    private static final String DB_USER = System.getenv("DB_USER") != null ? System.getenv("DB_USER") : "";
    private static final String DB_PASS = System.getenv("DB_PASS") != null ? System.getenv("DB_PASS") : "";

    // Match UserService naming
    public static void initialize() {
        try (Connection c = openConn();
             Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS products (" +
                            "id INTEGER PRIMARY KEY," +
                            "name TEXT NOT NULL," +
                            "description TEXT NOT NULL," +
                            "price DOUBLE PRECISION NOT NULL," +
                            "quantity INTEGER NOT NULL" +
                            ")"
            );

            // If table existed from an older version, ensure description exists
            try {
                st.executeUpdate("ALTER TABLE products ADD COLUMN description TEXT NOT NULL DEFAULT 'N/A'");
            } catch (SQLException ignored) {
                // duplicate column, ok
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize DB: " + e.getMessage(), e);
        }
    }

    private static Connection openConn() throws SQLException {
        if (!DB_USER.isEmpty()) {
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        }
        return DriverManager.getConnection(DB_URL);
    }

    public static boolean productExists(int id) throws SQLException {
        try (Connection c = openConn()) {
            return productExists(c, id);
        }
    }

    private static boolean productExists(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static ProductService.Product getProduct(int id) throws SQLException {
        try (Connection c = openConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, description, price, quantity FROM products WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ProductService.Product(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDouble("price"),
                        rs.getInt("quantity")
                );
            }
        }
    }

    // returns false if id already exists
    public static boolean createProduct(ProductService.Product p) throws SQLException {
        try (Connection c = openConn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO products(id, name, description, price, quantity) VALUES(?,?,?,?,?)")) {
                ps.setInt(1, p.id);
                ps.setString(2, p.name);
                ps.setString(3, p.description);
                ps.setDouble(4, p.price);
                ps.setInt(5, p.quantity);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isUniqueConstraint(e)) return false;
                throw e;
            }
        }
    }

    // returns false if id not found
    public static boolean updateProduct(ProductService.Product p) throws SQLException {
        try (Connection c = openConn();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE products SET name=?, description=?, price=?, quantity=? WHERE id=?")) {
            ps.setString(1, p.name);
            ps.setString(2, p.description);
            ps.setDouble(3, p.price);
            ps.setInt(4, p.quantity);
            ps.setInt(5, p.id);

            int affected = ps.executeUpdate();
            return affected > 0;
        }
    }

    public enum DeleteResult { NOT_FOUND, MISMATCH, DELETED }

    public static DeleteResult deleteProduct(int id, String name, double price, int quantity) throws SQLException {
        try (Connection c = openConn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM products WHERE id=? AND name=? AND price=? AND quantity=?")) {
                ps.setInt(1, id);
                ps.setString(2, name);
                ps.setDouble(3, price);
                ps.setInt(4, quantity);

                int affected = ps.executeUpdate();
                if (affected > 0) return DeleteResult.DELETED;
            }

            return productExists(c, id) ? DeleteResult.MISMATCH : DeleteResult.NOT_FOUND;
        }
    }

    private static boolean isUniqueConstraint(SQLException e) {
        String state = e.getSQLState();
        String message = e.getMessage();
        return "23505".equals(state)
                || e.getErrorCode() == 19
                || (message != null && message.toLowerCase().contains("unique constraint"));
    }

    public static void resetDatabase() {
        try (Connection conn = openConn();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("DROP TABLE IF EXISTS products");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset Product DB", e);
        }

        initialize();
    }
}
