package dev.parent.db;

import dev.parent.config.AppDirs;
import dev.parent.config.Log;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Embedded SQLite database = the local, always-available copy of the online
 * Supabase database. The UI only ever reads/writes this file; the
 * {@link SyncService} then exchanges deltas with Supabase in the background.
 *
 * Every entity table has two local-only helper columns:
 *   {@code _dirty}  1 = row changed locally and still has to be pushed
 *   (soft deletes are used everywhere so deletions sync too)
 */
public final class LocalDatabase {

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    /** Unchecked wrapper used by the DAO layer. */
    public static class DaoException extends RuntimeException {
        public DaoException(String message) {
            super(message);
        }

        public DaoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class NotEnoughStockException extends DaoException {
        public NotEnoughStockException(String medicineName, double remaining) {
            super("Not enough stock for \"" + medicineName + "\" ("
                    + String.format("%.2f", remaining) + " content unit(s) left)");
        }
    }

    private static Connection conn;
    private static volatile Runnable changeListener;

    private LocalDatabase() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static Path file() {
        return AppDirs.databaseFile();
    }

    public static synchronized void open() throws SQLException {
        if (conn != null) {
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver missing from the runtime", e);
        }
        Path file = file();
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=OFF"); // offline cache: keep it permissive
        }
        ensureSchema();
        Log.info("Local database ready: " + file);
    }

    public static synchronized void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            conn = null;
        }
    }

    public static boolean isOpen() {
        return conn != null;
    }

    private static Connection c() {
        if (conn == null) {
            throw new DaoException("Local database is not open");
        }
        return conn;
    }

    /** Runs a task against the single shared connection, serialized. */
    public static synchronized <T> T exec(SqlTask<T> task) {
        try {
            return task.run(c());
        } catch (SQLException e) {
            throw new DaoException(e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- schema

    private static void ensureSchema() throws SQLException {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS medicines (" +
                        "uuid TEXT PRIMARY KEY, id INTEGER, barcode TEXT, name TEXT, type TEXT," +
                        "size REAL DEFAULT 0, full_size REAL DEFAULT 0, buy_price REAL DEFAULT 0," +
                        "sell_price REAL DEFAULT 0, expiry_date TEXT, stock INTEGER DEFAULT 0," +
                        "seller TEXT, description TEXT, low_stock_threshold INTEGER DEFAULT 3," +
                        "created_at TEXT, updated_at TEXT, is_deleted INTEGER DEFAULT 0," +
                        "_dirty INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS clients (" +
                        "uuid TEXT PRIMARY KEY, id INTEGER, name TEXT, phone TEXT, address TEXT," +
                        "description TEXT, created_at TEXT, updated_at TEXT," +
                        "is_deleted INTEGER DEFAULT 0, _dirty INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS client_animals (" +
                        "uuid TEXT PRIMARY KEY, id INTEGER, client_uuid TEXT, category TEXT," +
                        "species TEXT, name TEXT, breed TEXT, gender TEXT, birth_date TEXT," +
                        "quantity INTEGER DEFAULT 1, notes TEXT, created_at TEXT, updated_at TEXT," +
                        "is_deleted INTEGER DEFAULT 0, _dirty INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS transactions (" +
                        "uuid TEXT PRIMARY KEY, id INTEGER, client_uuid TEXT, medicine_uuid TEXT," +
                        "created_at TEXT, created_date TEXT, quantity REAL DEFAULT 0," +
                        "amount REAL DEFAULT 0, type TEXT, description TEXT, paid INTEGER DEFAULT 0," +
                        "updated_at TEXT, is_deleted INTEGER DEFAULT 0," +
                        "_dirty INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS appointments (" +
                        "uuid TEXT PRIMARY KEY, id INTEGER, client_uuid TEXT, appointment_date TEXT," +
                        "is_done INTEGER DEFAULT 0, description TEXT, created_at TEXT," +
                        "updated_at TEXT, is_deleted INTEGER DEFAULT 0," +
                        "_dirty INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS sync_meta (" +
                        "table_name TEXT PRIMARY KEY, last_pull TEXT)",
                "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)"
        };
        try (Statement st = c().createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }

    // -------------------------------------------------------------- local change

    /** SyncService installs itself here; DAOs call {@link #markChanged()} after writes. */
    public static void onLocalChange(Runnable listener) {
        changeListener = listener;
    }

    public static void markChanged() {
        Runnable l = changeListener;
        if (l != null) {
            l.run();
        }
    }

    // ------------------------------------------------------------- sync helpers

    /** Resolves the server numeric id for a locally cached row (null when never pushed yet). */
    public static Long serverId(String table, String uuid) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        return rs.wasNull() ? null : id;
                    }
                    return null;
                }
            }
        });
    }

    public static Instant lastPull(String table) {
        String raw = kv("last_pull:" + table);
        return raw == null ? null : Json.parseInstant(raw);
    }

    public static void setLastPull(String table, Instant when) {
        kv("last_pull:" + table, when == null ? null : when.toString());
    }

    public static String kv(String key) {
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kv WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public static void kv(String key, String value) {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kv(key, value) VALUES(?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                if (value == null) {
                    ps.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(2, value);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Number of local changes still waiting to be uploaded. */
    public static int pendingCount() {
        int total = 0;
        for (String t : new String[]{"medicines", "clients", "client_animals", "transactions", "appointments"}) {
            total += exec(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t + " WHERE _dirty = 1")) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        }
        return total;
    }
}
