package library;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A row of the {@code loans} table, plus the joined book/student labels. */
public final class Loan {

    public final int id;
    public final int bookId;
    public final int studentId;
    public final String startDate;
    public final String endDate;
    public final String status;
    public final String requestedAt;
    public final Integer approvedBy;
    public final Integer deliveredBy;
    public final String deliveredAt;
    public final Integer returnedBy;
    public final String returnedAt;

    /** Filled in by the queries that join {@code books} / {@code users}. */
    public final String bookTitle;
    public final String bookAuthor;
    public final String studentUsername;

    public Loan(int id, int bookId, int studentId, String startDate, String endDate, String status,
                String requestedAt, Integer approvedBy, Integer deliveredBy, String deliveredAt,
                Integer returnedBy, String returnedAt,
                String bookTitle, String bookAuthor, String studentUsername) {
        this.id = id;
        this.bookId = bookId;
        this.studentId = studentId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.requestedAt = requestedAt;
        this.approvedBy = approvedBy;
        this.deliveredBy = deliveredBy;
        this.deliveredAt = deliveredAt;
        this.returnedBy = returnedBy;
        this.returnedAt = returnedAt;
        this.bookTitle = bookTitle;
        this.bookAuthor = bookAuthor;
        this.studentUsername = studentUsername;
    }

    public static Loan from(ResultSet rs) throws SQLException {
        return new Loan(
                rs.getInt("id"),
                rs.getInt("book_id"),
                rs.getInt("student_id"),
                rs.getString("start_date"),
                rs.getString("end_date"),
                rs.getString("status"),
                rs.getString("requested_at"),
                Book.intOrNull(rs, "approved_by"),
                Book.intOrNull(rs, "delivered_by"),
                rs.getString("delivered_at"),
                Book.intOrNull(rs, "returned_by"),
                rs.getString("returned_at"),
                column(rs, "book_title"),
                column(rs, "book_author"),
                column(rs, "student_username"));
    }

    /** Null when the optional joined column is not part of the query. */
    private static String column(ResultSet rs, String name) throws SQLException {
        try {
            return rs.getString(name);
        } catch (SQLException e) {
            return null;
        }
    }
}
