package library;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database layer: connection, schema and seed data (SQLite).
 *
 * <p>The schema and the default manager account are identical to the Python
 * version, so both implementations can share the same {@code library.db}.</p>
 */
public final class Db {

    private static final String SCHEMA =
            "CREATE TABLE IF NOT EXISTS users (\n"
            + "    id            INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    username      TEXT    UNIQUE NOT NULL,\n"
            + "    password_hash TEXT    NOT NULL,\n"
            + "    full_name     TEXT    DEFAULT '',\n"
            + "    role          TEXT    NOT NULL CHECK (role IN ('student', 'employee', 'manager')),\n"
            + "    is_active     INTEGER NOT NULL DEFAULT 1,\n"
            + "    created_at    TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))\n"
            + ");\n"
            + "CREATE TABLE IF NOT EXISTS books (\n"
            + "    id           INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    title        TEXT    NOT NULL,\n"
            + "    author       TEXT    NOT NULL,\n"
            + "    publish_year INTEGER,\n"
            + "    is_available INTEGER NOT NULL DEFAULT 1,\n"
            + "    created_by   INTEGER REFERENCES users(id),\n"
            + "    created_at   TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))\n"
            + ");\n"
            + "CREATE TABLE IF NOT EXISTS loans (\n"
            + "    id           INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    book_id      INTEGER NOT NULL REFERENCES books(id),\n"
            + "    student_id   INTEGER NOT NULL REFERENCES users(id),\n"
            + "    start_date   TEXT    NOT NULL,\n"
            + "    end_date     TEXT    NOT NULL,\n"
            + "    status       TEXT    NOT NULL DEFAULT 'pending'\n"
            + "                 CHECK (status IN ('pending', 'approved', 'rejected', 'borrowed', 'returned')),\n"
            + "    requested_at TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),\n"
            + "    approved_by  INTEGER REFERENCES users(id),\n"
            + "    delivered_by INTEGER REFERENCES users(id),\n"
            + "    delivered_at TEXT,\n"
            + "    returned_by  INTEGER REFERENCES users(id),\n"
            + "    returned_at  TEXT\n"
            + ");";

    private static String dbPath = defaultPath();

    private Db() {
    }

    private static String defaultPath() {
        String override = System.getProperty("library.db");
        File file = (override == null || override.isEmpty()) ? new File("library.db") : new File(override);
        return file.getAbsolutePath();
    }

    public static String path() {
        return dbPath;
    }

    public static Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    public static void init() {
        init(dbPath);
    }

    public static void init(String path) {
        dbPath = new File(path).getAbsolutePath();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String statement : SCHEMA.split(";")) {
                if (!statement.trim().isEmpty()) {
                    st.execute(statement);
                }
            }
            // Seed the default library manager account (boss) on first run.
            if (!hasManager(conn)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users (username, password_hash, full_name, role) VALUES (?, ?, ?, 'manager')")) {
                    ps.setString(1, "admin");
                    ps.setString(2, Auth.hashPassword("admin123"));
                    ps.setString(3, "Library Manager");
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize the database at " + path + ": " + e.getMessage(), e);
        }
    }

    private static boolean hasManager(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM users WHERE role = 'manager'")) {
            return rs.next();
        }
    }

    public static void main(String[] args) {
        init();
        System.out.println("Database initialized at " + path());
    }
}
