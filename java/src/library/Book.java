package library;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A row of the {@code books} table. */
public final class Book {

    public final int id;
    public final String title;
    public final String author;
    public final Integer publishYear;
    public final boolean available;
    public final Integer createdBy;
    public final String createdAt;

    public Book(int id, String title, String author, Integer publishYear,
                boolean available, Integer createdBy, String createdAt) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.publishYear = publishYear;
        this.available = available;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static Book from(ResultSet rs) throws SQLException {
        return new Book(
                rs.getInt("id"),
                rs.getString("title"),
                rs.getString("author"),
                intOrNull(rs, "publish_year"),
                rs.getInt("is_available") == 1,
                intOrNull(rs, "created_by"),
                rs.getString("created_at"));
    }

    public static Book fromSearchRow(ResultSet rs, boolean withAvailability) throws SQLException {
        return new Book(
                rs.getInt("id"),
                rs.getString("title"),
                rs.getString("author"),
                intOrNull(rs, "publish_year"),
                withAvailability && rs.getInt("is_available") == 1,
                null,
                null);
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
