package library;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A row of the {@code users} table. */
public final class User {

    public final int id;
    public final String username;
    public final String passwordHash;
    public final String fullName;
    public final String role;
    public final boolean active;
    public final String createdAt;

    public User(int id, String username, String passwordHash, String fullName,
                String role, boolean active, String createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static User from(ResultSet rs) throws SQLException {
        return new User(
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("role"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at"));
    }

    public boolean isStudent() {
        return "student".equals(role);
    }

    public boolean isEmployee() {
        return "employee".equals(role);
    }

    public boolean isManager() {
        return "manager".equals(role);
    }
}
