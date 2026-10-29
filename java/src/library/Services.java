package library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.format.SignStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Business logic for the university library management system.
 *
 * <p>Roles and main flows:</p>
 * <ul>
 *   <li>student  : register/login, search books, request a loan for a date range</li>
 *   <li>guest    : student count, title-only search, simple stats</li>
 *   <li>employee : manage books, approve/deliver/receive loans, student reports</li>
 *   <li>manager  : create employees, employee/loan/student statistics</li>
 * </ul>
 *
 * <p>This is a behaviour-for-behaviour port of {@code services.py}.</p>
 */
public final class Services {

    public static final String DATE_FMT = "yyyy-MM-dd";
    public static final String NOW_FMT = "yyyy-MM-dd HH:mm:ss";

    /** Accepts both {@code 2024-01-05} and {@code 2024-1-5}, like Python's strptime. */
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .toFormatter();

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern(NOW_FMT);

    private Services() {
    }

    // ---------------------------------------------------------------- utilities

    public static String nowStr() {
        return LocalDateTime.now().format(NOW_FORMAT);
    }

    public static LocalDate today() {
        return LocalDate.now();
    }

    /** Return a date object or null if the input is invalid. */
    public static LocalDate parseDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- users

    /** 1-1 Student self-registration (active by default). */
    public static int registerStudent(Connection conn, String username, String password, String fullName)
            throws SQLException {
        if (isBlank(username) || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Username and password are required.");
        }
        if (usernameTaken(conn, username)) {
            throw new IllegalArgumentException("This username is already taken.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, full_name, role, is_active) "
                + "VALUES (?, ?, ?, 'student', 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username.trim());
            ps.setString(2, Auth.hashPassword(password));
            ps.setString(3, nullToEmpty(fullName).trim());
            ps.executeUpdate();
            int id = generatedId(ps, conn);
            conn.commit();
            return id;
        }
    }

    /** 4-1 Manager creates a library employee account. */
    public static int createEmployee(Connection conn, int managerId, String username, String password,
                                     String fullName) throws SQLException {
        if (isBlank(username) || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Username and password are required.");
        }
        if (usernameTaken(conn, username)) {
            throw new IllegalArgumentException("This username is already taken.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, full_name, role, is_active) "
                + "VALUES (?, ?, ?, 'employee', 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username.trim());
            ps.setString(2, Auth.hashPassword(password));
            ps.setString(3, nullToEmpty(fullName).trim());
            ps.executeUpdate();
            int id = generatedId(ps, conn);
            conn.commit();
            return id;
        }
    }

    /** 1-2 / 3-1 Login. Returns the user or null. */
    public static User login(Connection conn, String username, String password, String role) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                User user = User.from(rs);
                if (!Auth.verifyPassword(password, user.passwordHash)) {
                    return null;
                }
                if (role != null && !role.equals(user.role)) {
                    return null;
                }
                return user;
            }
        }
    }

    /** 3-2 Change password after verifying the old one. */
    public static void changePassword(Connection conn, int userId, String oldPassword, String newPassword)
            throws SQLException {
        User user = findUser(conn, userId);
        if (user == null || !Auth.verifyPassword(oldPassword, user.passwordHash)) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("New password cannot be empty.");
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, Auth.hashPassword(newPassword));
            ps.setInt(2, userId);
            ps.executeUpdate();
            conn.commit();
        }
    }

    /** 3-7 Enable/disable a student (disabled students cannot borrow). */
    public static void setStudentActive(Connection conn, int studentId, boolean active) throws SQLException {
        if (findStudent(conn, studentId) == null) {
            throw new IllegalArgumentException("Student not found.");
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET is_active = ? WHERE id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, studentId);
            ps.executeUpdate();
            conn.commit();
        }
    }

    public static List<User> listStudents(Connection conn) throws SQLException {
        List<User> users = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM users WHERE role = 'student' ORDER BY username")) {
            while (rs.next()) {
                users.add(User.from(rs));
            }
        }
        return users;
    }

    public static int studentCount(Connection conn) throws SQLException {
        return scalar(conn, "SELECT COUNT(*) AS c FROM users WHERE role = 'student'");
    }

    // ------------------------------------------------------------------- books

    /** 3-3 Register a new book. */
    public static int addBook(Connection conn, int employeeId, String title, String author, Integer publishYear)
            throws SQLException {
        if (isBlank(title) || isBlank(author)) {
            throw new IllegalArgumentException("Title and author are required.");
        }
        if (publishYear != null && publishYear <= 0) {
            throw new IllegalArgumentException("Publish year must be a positive number.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO books (title, author, publish_year, created_by) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title.trim());
            ps.setString(2, author.trim());
            if (publishYear == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, publishYear);
            }
            ps.setInt(4, employeeId);
            ps.executeUpdate();
            int id = generatedId(ps, conn);
            conn.commit();
            return id;
        }
    }

    /**
     * 1-3 / 2-2 Search books by a combination of title / author / year.
     * Empty filters are ignored, so any subset can be combined.
     */
    public static List<Book> searchBooks(Connection conn, String title, String author, Integer publishYear,
                                         boolean showAvailability) throws SQLException {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!isBlank(title)) {
            conditions.add("title LIKE ?");
            params.add("%" + title + "%");
        }
        if (!isBlank(author)) {
            conditions.add("author LIKE ?");
            params.add("%" + author + "%");
        }
        if (publishYear != null) {
            conditions.add("publish_year = ?");
            params.add(publishYear);
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        String cols = "id, title, author, publish_year" + (showAvailability ? ", is_available" : "");

        List<Book> books = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + cols + " FROM books " + where + " ORDER BY title")) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    books.add(Book.fromSearchRow(rs, showAvailability));
                }
            }
        }
        return books;
    }

    public static Book getBook(Connection conn, int bookId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM books WHERE id = ?")) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Book.from(rs) : null;
            }
        }
    }

    // ------------------------------------------------------------------- loans

    /** 1-4 Student requests a loan for a [start, end] date range. */
    public static int requestLoan(Connection conn, int studentId, int bookId, String startDate, String endDate)
            throws SQLException {
        User student = findStudent(conn, studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }
        if (!student.active) {
            throw new IllegalArgumentException("Your account is disabled; you cannot borrow books.");
        }

        Book book = getBook(conn, bookId);
        if (book == null) {
            throw new IllegalArgumentException("Book not found.");
        }
        if (!book.available) {
            throw new IllegalArgumentException("This book is currently on loan.");
        }

        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start == null || end == null) {
            throw new IllegalArgumentException("Dates must use the " + DATE_FMT + " format.");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date must be on or after the start date.");
        }
        if (start.isBefore(today())) {
            throw new IllegalArgumentException("Start date cannot be in the past.");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO loans (book_id, student_id, start_date, end_date, status) "
                + "VALUES (?, ?, ?, ?, 'pending')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, bookId);
            ps.setInt(2, studentId);
            ps.setString(3, start.format(DateTimeFormatter.ISO_LOCAL_DATE));
            ps.setString(4, end.format(DateTimeFormatter.ISO_LOCAL_DATE));
            ps.executeUpdate();
            int id = generatedId(ps, conn);
            conn.commit();
            return id;
        }
    }

    /** 3-5 Loan requests whose start date is today or yesterday. */
    public static List<Loan> pendingRequestsForToday(Connection conn) throws SQLException {
        List<Loan> all = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT l.*, b.title AS book_title, u.username AS student_username "
                     + "FROM loans l "
                     + "JOIN books b ON b.id = l.book_id "
                     + "JOIN users u ON u.id = l.student_id "
                     + "WHERE l.status = 'pending'")) {
            while (rs.next()) {
                all.add(Loan.from(rs));
            }
        }
        LocalDate yesterday = today().minusDays(1);
        List<Loan> result = new ArrayList<>();
        for (Loan loan : all) {
            LocalDate start = LocalDate.parse(loan.startDate);
            if (start.equals(yesterday) || start.equals(today())) {
                result.add(loan);
            }
        }
        return result;
    }

    /**
     * 3-5 Approve a pending request and mark the book as on loan.
     * Any other pending requests for the same book are rejected.
     */
    public static void approveRequest(Connection conn, int employeeId, int loanId) throws SQLException {
        Loan loan = findLoan(conn, loanId);
        if (loan == null) {
            throw new IllegalArgumentException("Loan request not found.");
        }
        if (!"pending".equals(loan.status)) {
            throw new IllegalArgumentException("This request has already been processed.");
        }
        LocalDate start = LocalDate.parse(loan.startDate);
        if (!start.equals(today().minusDays(1)) && !start.equals(today())) {
            throw new IllegalArgumentException("Only requests starting today or yesterday can be approved.");
        }
        Book book = getBook(conn, loan.bookId);
        if (book == null || !book.available) {
            throw new IllegalArgumentException("The book is no longer available.");
        }

        try (PreparedStatement ps = conn.prepareStatement("UPDATE books SET is_available = 0 WHERE id = ?")) {
            ps.setInt(1, loan.bookId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE loans SET status = 'approved', approved_by = ? WHERE id = ?")) {
            ps.setInt(1, employeeId);
            ps.setInt(2, loanId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE loans SET status = 'rejected' "
                + "WHERE book_id = ? AND status = 'pending' AND id != ?")) {
            ps.setInt(1, loan.bookId);
            ps.setInt(2, loanId);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public static void rejectRequest(Connection conn, int loanId) throws SQLException {
        Loan loan = findLoan(conn, loanId);
        if (loan == null || !"pending".equals(loan.status)) {
            throw new IllegalArgumentException("Pending request not found.");
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE loans SET status = 'rejected' WHERE id = ?")) {
            ps.setInt(1, loanId);
            ps.executeUpdate();
            conn.commit();
        }
    }

    /** After approval the student picks the book up; employee records delivery. */
    public static void deliverBook(Connection conn, int employeeId, int loanId) throws SQLException {
        Loan loan = findLoan(conn, loanId);
        if (loan == null || !"approved".equals(loan.status)) {
            throw new IllegalArgumentException("Only approved requests can be delivered.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE loans SET status = 'borrowed', delivered_by = ?, delivered_at = ? WHERE id = ?")) {
            ps.setInt(1, employeeId);
            ps.setString(2, nowStr());
            ps.setInt(3, loanId);
            ps.executeUpdate();
            conn.commit();
        }
    }

    /** 3-8 Receive a borrowed book back and record the return time. */
    public static void receiveReturn(Connection conn, int employeeId, int loanId) throws SQLException {
        Loan loan = findLoan(conn, loanId);
        if (loan == null || !"borrowed".equals(loan.status)) {
            throw new IllegalArgumentException("Only borrowed books can be received back.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE loans SET status = 'returned', returned_by = ?, returned_at = ? WHERE id = ?")) {
            ps.setInt(1, employeeId);
            ps.setString(2, nowStr());
            ps.setInt(3, loanId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE books SET is_available = 1 WHERE id = ?")) {
            ps.setInt(1, loan.bookId);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public static List<Loan> loansForStudent(Connection conn, int studentId) throws SQLException {
        List<Loan> loans = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT l.*, b.title AS book_title, b.author AS book_author "
                + "FROM loans l JOIN books b ON b.id = l.book_id "
                + "WHERE l.student_id = ? ORDER BY l.requested_at DESC")) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loans.add(Loan.from(rs));
                }
            }
        }
        return loans;
    }

    /** 3-6 History + stats for one student. */
    public static StudentLoanReport studentLoanReport(Connection conn, int studentId) throws SQLException {
        List<Loan> loans = loansForStudent(conn, studentId);
        int notReturned = 0;
        int late = 0;
        for (Loan loan : loans) {
            if ("borrowed".equals(loan.status)) {
                notReturned++;
            }
            if (isLate(loan)) {
                late++;
            }
        }
        return new StudentLoanReport(loans, new StudentStats(loans.size(), notReturned, late));
    }

    /** A loan is late if it was returned after its end date. */
    public static boolean isLate(Loan loan) {
        if (!"returned".equals(loan.status) || loan.returnedAt == null) {
            return false;
        }
        return LocalDate.parse(loan.returnedAt.substring(0, 10)).isAfter(LocalDate.parse(loan.endDate));
    }

    public static int lateDays(Loan loan) {
        if (!isLate(loan)) {
            return 0;
        }
        return (int) (LocalDate.parse(loan.returnedAt.substring(0, 10)).toEpochDay()
                - LocalDate.parse(loan.endDate).toEpochDay());
    }

    /** 2-3 Most recent books that are currently on loan. */
    public static List<Loan> borrowedBooks(Connection conn, int limit) throws SQLException {
        List<Loan> loans = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT l.*, b.title AS book_title, b.author AS book_author, "
                + "u.username AS student_username "
                + "FROM loans l "
                + "JOIN books b ON b.id = l.book_id "
                + "JOIN users u ON u.id = l.student_id "
                + "WHERE l.status = 'borrowed' "
                + "ORDER BY l.delivered_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loans.add(Loan.from(rs));
                }
            }
        }
        return loans;
    }

    /** 2-3 Guest statistics. */
    public static SimpleStats simpleStats(Connection conn) throws SQLException {
        return new SimpleStats(
                studentCount(conn),
                scalar(conn, "SELECT COUNT(*) AS c FROM books"),
                scalar(conn, "SELECT COUNT(*) AS c FROM loans"),
                scalar(conn, "SELECT COUNT(*) AS c FROM loans WHERE status = 'borrowed'"));
    }

    // ------------------------------------------------------- manager reporting

    /** 4-2 Books registered / loans delivered / returns received by an employee. */
    public static EmployeePerformance employeePerformance(Connection conn, int employeeId) throws SQLException {
        return new EmployeePerformance(
                scalar(conn, "SELECT COUNT(*) AS c FROM books WHERE created_by = ?", employeeId),
                scalar(conn, "SELECT COUNT(*) AS c FROM loans WHERE delivered_by = ?", employeeId),
                scalar(conn, "SELECT COUNT(*) AS c FROM loans WHERE returned_by = ?", employeeId));
    }

    /** 4-3 Overall loan statistics. */
    public static LoanStatistics loanStatistics(Connection conn) throws SQLException {
        int totalRequests = scalar(conn, "SELECT COUNT(*) AS c FROM loans");
        int totalDelivered = scalar(conn, "SELECT COUNT(*) AS c FROM loans WHERE delivered_at IS NOT NULL");

        long totalDays = 0;
        int count = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT delivered_at, returned_at FROM loans "
                     + "WHERE status = 'returned' AND delivered_at IS NOT NULL AND returned_at IS NOT NULL")) {
            while (rs.next()) {
                LocalDate delivered = LocalDate.parse(rs.getString("delivered_at").substring(0, 10));
                LocalDate returned = LocalDate.parse(rs.getString("returned_at").substring(0, 10));
                totalDays += returned.toEpochDay() - delivered.toEpochDay();
                count++;
            }
        }
        double avg = count == 0 ? 0.0 : (double) totalDays / count;
        avg = Math.round(avg * 100.0) / 100.0;
        return new LoanStatistics(totalRequests, totalDelivered, avg);
    }

    /** 4-4 Per-student stats (as 3-6) for every student + top-10 late students. */
    public static AllStudentsReport allStudentsReport(Connection conn) throws SQLException {
        List<StudentReport> reports = new ArrayList<>();
        for (User student : listStudents(conn)) {
            StudentLoanReport report = studentLoanReport(conn, student.id);
            int totalLateDays = 0;
            for (Loan loan : report.loans) {
                totalLateDays += lateDays(loan);
            }
            reports.add(new StudentReport(student, report.stats, totalLateDays));
        }
        List<StudentReport> topLate = new ArrayList<>(reports);
        // stable sort, highest late days first (mirrors Python's sorted(..., reverse=True))
        topLate.sort(Comparator.comparingInt((StudentReport r) -> r.lateDays).reversed());
        if (topLate.size() > 10) {
            topLate = new ArrayList<>(topLate.subList(0, 10));
        }
        return new AllStudentsReport(reports, topLate);
    }

    // --------------------------------------------------------------- internals

    private static boolean usernameTaken(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static User findUser(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? User.from(rs) : null;
            }
        }
    }

    private static User findStudent(Connection conn, int studentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM users WHERE id = ? AND role = 'student'")) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? User.from(rs) : null;
            }
        }
    }

    public static Loan findLoan(Connection conn, int loanId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM loans WHERE id = ?")) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Loan.from(rs) : null;
            }
        }
    }

    private static int scalar(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, java.util.Arrays.asList(params));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static int generatedId(PreparedStatement ps, Connection conn) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getInt(1);
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------ result types

    public static final class StudentStats {
        public final int total;
        public final int notReturned;
        public final int late;

        public StudentStats(int total, int notReturned, int late) {
            this.total = total;
            this.notReturned = notReturned;
            this.late = late;
        }
    }

    public static final class StudentLoanReport {
        public final List<Loan> loans;
        public final StudentStats stats;

        public StudentLoanReport(List<Loan> loans, StudentStats stats) {
            this.loans = loans;
            this.stats = stats;
        }
    }

    public static final class SimpleStats {
        public final int students;
        public final int books;
        public final int loans;
        public final int borrowed;

        public SimpleStats(int students, int books, int loans, int borrowed) {
            this.students = students;
            this.books = books;
            this.loans = loans;
            this.borrowed = borrowed;
        }
    }

    public static final class EmployeePerformance {
        public final int booksRegistered;
        public final int loansDelivered;
        public final int returnsReceived;

        public EmployeePerformance(int booksRegistered, int loansDelivered, int returnsReceived) {
            this.booksRegistered = booksRegistered;
            this.loansDelivered = loansDelivered;
            this.returnsReceived = returnsReceived;
        }
    }

    public static final class LoanStatistics {
        public final int totalRequests;
        public final int totalDelivered;
        public final double avgLoanDays;

        public LoanStatistics(int totalRequests, int totalDelivered, double avgLoanDays) {
            this.totalRequests = totalRequests;
            this.totalDelivered = totalDelivered;
            this.avgLoanDays = avgLoanDays;
        }
    }

    public static final class StudentReport {
        public final User student;
        public final StudentStats stats;
        public final int lateDays;

        public StudentReport(User student, StudentStats stats, int lateDays) {
            this.student = student;
            this.stats = stats;
            this.lateDays = lateDays;
        }
    }

    public static final class AllStudentsReport {
        public final List<StudentReport> reports;
        public final List<StudentReport> topLate;

        public AllStudentsReport(List<StudentReport> reports, List<StudentReport> topLate) {
            this.reports = reports;
            this.topLate = topLate;
        }
    }
}
