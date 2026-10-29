package library;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Seed the library database with realistic random data.
 *
 * <p>Generates: 3 employees (in addition to the manager), 20 students, 20 books and
 * 20 loans in mixed statuses (pending / approved / borrowed / returned, some of them
 * late) so that every report has data to show.</p>
 *
 * <p>Run: {@code java -cp "out;lib/sqlite-jdbc.jar" library.SeedData}<br>
 * Note: it deletes the existing library.db and creates a fresh one.</p>
 */
public final class SeedData {

    private static final String[] FIRST_NAMES = {
            "Ali", "Sara", "Reza", "Maryam", "Hossein", "Zahra", "Amir", "Neda",
            "Mohammad", "Fateme", "Saeed", "Parisa", "Mahdi", "Niloofar", "Kaveh",
            "Shirin", "Hamed", "Leyla", "Omid", "Sahar"
    };

    private static final String[] LAST_NAMES = {
            "Ahmadi", "Karimi", "Hosseini", "Moradi", "Rahimi", "Ghorbani",
            "Sadeghi", "Ebrahimi", "Nazari", "Jafari"
    };

    private static final String[] TITLES = {
            "Introduction to Algorithms", "Software Engineering", "Database Systems",
            "Operating System Concepts", "Computer Networks", "Artificial Intelligence",
            "Clean Code", "Design Patterns", "Discrete Mathematics", "Linear Algebra",
            "Calculus", "Data Structures in Python", "Machine Learning Basics",
            "Compiler Design", "Computer Architecture", "Statistics for Engineers",
            "Web Development with Flask", "Cryptography and Security",
            "Theory of Computation", "Graph Theory"
    };

    private static final String[] AUTHORS = {
            "Cormen et al.", "Sommerville", "Elmasri & Navathe", "Silberschatz",
            "Tanenbaum", "Russell & Norvig", "Robert C. Martin", "GoF",
            "Rosen", "Strang", "Stewart", "Goodrich", "Mitchell",
            "Aho & Ullman", "Patterson & Hennessy", "Montgomery",
            "Grinberg", "Stallings", "Sipser", "Diestel"
    };

    private static final Random RANDOM = new Random();

    private SeedData() {
    }

    public static void main(String[] args) {
        try {
            seed(args.length > 0 ? args[0] : null);
        } catch (SQLException e) {
            System.err.println("Seeding failed: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void seed(String dbPath) throws SQLException {
        if (dbPath == null) {
            dbPath = Db.path();
        }
        File file = new File(dbPath);
        if (file.exists() && !file.delete()) {
            System.err.println("Cannot delete the existing database: " + file);
            System.exit(1);
        }
        Db.init(dbPath);

        try (Connection conn = Db.connect()) {
            LocalDate today = Services.today();

            // --- manager (seeded automatically) + 3 employees
            List<Integer> employeeIds = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                employeeIds.add(insertUser(conn, "employee" + i, "emp" + i + "pass", "Employee " + i, "employee", true));
            }

            // --- 20 students
            List<String> names = new ArrayList<>(Arrays.asList(FIRST_NAMES));
            Collections.shuffle(names, RANDOM);
            List<Integer> studentIds = new ArrayList<>();
            List<String> used = new ArrayList<>();
            int index = 1;
            for (String first : names) {
                String username = first.toLowerCase() + index;
                while (used.contains(username)) {
                    username += RANDOM.nextInt(10);
                }
                used.add(username);
                String fullName = first + " " + LAST_NAMES[RANDOM.nextInt(LAST_NAMES.length)];
                studentIds.add(insertUser(conn, username, "student123", fullName, "student", true));
                index++;
            }
            // a couple of disabled students to test rule 3-7
            List<Integer> shuffledStudents = new ArrayList<>(studentIds);
            Collections.shuffle(shuffledStudents, RANDOM);
            for (int i = 0; i < 2; i++) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET is_active = 0 WHERE id = ?")) {
                    ps.setInt(1, shuffledStudents.get(i));
                    ps.executeUpdate();
                }
            }

            // --- 20 books (one per title), registered by random employees
            List<Integer> bookIds = new ArrayList<>();
            for (int i = 0; i < TITLES.length; i++) {
                int year = 2005 + RANDOM.nextInt(21);
                bookIds.add(insertBook(conn, TITLES[i], AUTHORS[i], year, randomOf(employeeIds)));
            }

            // --- 20 loans in mixed statuses
            List<Integer> availableBooks = new ArrayList<>(bookIds);
            Collections.shuffle(availableBooks, RANDOM);
            List<Integer> allStudentIds = studentIds(conn);
            int pending = 0;
            int approved = 0;
            int borrowed = 0;
            int returned = 0;
            int rejected = 0;

            // 5 returned loans: 3 of them late (returned after end_date)
            for (int i = 0; i < 5; i++) {
                int bookId = availableBooks.remove(availableBooks.size() - 1);
                LocalDate start = randomDateBetween(today.minusDays(90), today.minusDays(20));
                LocalDate end = start.plusDays(7 + RANDOM.nextInt(14));
                int loanId = makeLoan(conn, bookId, "returned", start, end, randomOf(employeeIds), allStudentIds);
                LocalDate returnDay = (i < 3)
                        ? end.plusDays(1 + RANDOM.nextInt(15))
                        : randomDateBetween(start.plusDays(2), end);
                updateLoanReturn(conn, loanId, returnDay, randomOf(employeeIds));
                returned++;
            }

            // 5 currently borrowed (on loan now)
            for (int i = 0; i < 5; i++) {
                int bookId = availableBooks.remove(availableBooks.size() - 1);
                LocalDate start = randomDateBetween(today.minusDays(15), today.minusDays(3));
                LocalDate end = start.plusDays(10 + RANDOM.nextInt(21));
                makeLoan(conn, bookId, "borrowed", start, end, randomOf(employeeIds), allStudentIds);
                setBookAvailable(conn, bookId, false);
                borrowed++;
            }

            // 4 approved (waiting for the student to pick the book up)
            for (int i = 0; i < 4; i++) {
                int bookId = availableBooks.remove(availableBooks.size() - 1);
                LocalDate start = today;
                LocalDate end = start.plusDays(10 + RANDOM.nextInt(16));
                makeLoan(conn, bookId, "approved", start, end, randomOf(employeeIds), allStudentIds);
                setBookAvailable(conn, bookId, false);
                approved++;
            }

            // 3 pending review by the employee (start = today or yesterday)
            for (int i = 0; i < 3; i++) {
                int bookId = availableBooks.remove(availableBooks.size() - 1);
                LocalDate start = today.minusDays(RANDOM.nextInt(2));
                LocalDate end = start.plusDays(7 + RANDOM.nextInt(14));
                makeLoan(conn, bookId, "pending", start, end, null, allStudentIds);
                pending++;
            }

            // 3 rejected requests (books stay available)
            for (int i = 0; i < 3; i++) {
                int bookId = bookIds.get(RANDOM.nextInt(bookIds.size()));
                LocalDate start = randomDateBetween(today.minusDays(30), today.minusDays(10));
                LocalDate end = start.plusDays(10);
                makeLoan(conn, bookId, "rejected", start, end, null, allStudentIds);
                rejected++;
            }

            conn.commit();

            int students = scalar(conn, "SELECT COUNT(*) c FROM users WHERE role='student'");
            int employees = scalar(conn, "SELECT COUNT(*) c FROM users WHERE role='employee'");
            int books = scalar(conn, "SELECT COUNT(*) c FROM books");
            int loans = scalar(conn, "SELECT COUNT(*) c FROM loans");

            System.out.println("Seeded database: " + Db.path());
            System.out.println("  students : " + students + " (2 of them disabled)");
            System.out.println("  employees: " + employees + " (employee1..3, password emp1pass/emp2pass/emp3pass)");
            System.out.println("  manager  : 1 (admin / admin123)");
            System.out.println("  books    : " + books + " (password for all students: student123)");
            System.out.println("  loans    : " + loans + " -> {pending=" + pending + ", approved=" + approved
                    + ", borrowed=" + borrowed + ", returned=" + returned + ", rejected=" + rejected
                    + "} (3 of the returned ones are late)");
            System.out.println("Passwords: manager=admin123 | employees=emp1pass, emp2pass, emp3pass | students=student123");
        }
    }

    // --------------------------------------------------------------- internals

    private static int insertUser(Connection conn, String username, String password, String fullName,
                                  String role, boolean active) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash, full_name, role, is_active) "
                + "VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, Auth.hashPassword(password));
            ps.setString(3, fullName);
            ps.setString(4, role);
            ps.setInt(5, active ? 1 : 0);
            ps.executeUpdate();
            return lastId(ps, conn);
        }
    }

    private static int insertBook(Connection conn, String title, String author, int year, int employeeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO books (title, author, publish_year, is_available, created_by) "
                + "VALUES (?, ?, ?, 1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.setString(2, author);
            ps.setInt(3, year);
            ps.setInt(4, employeeId);
            ps.executeUpdate();
            return lastId(ps, conn);
        }
    }

    private static int makeLoan(Connection conn, int bookId, String status, LocalDate start, LocalDate end,
                                Integer employeeId, List<Integer> studentIds) throws SQLException {
        boolean approvedish = status.equals("approved") || status.equals("borrowed") || status.equals("returned");
        boolean deliveredish = status.equals("borrowed") || status.equals("returned");

        String requestedAt = start + " " + String.format(Locale.ROOT, "%02d:%02d:00",
                8 + RANDOM.nextInt(11), 10 + RANDOM.nextInt(50));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO loans (book_id, student_id, start_date, end_date, status, "
                + "requested_at, approved_by, delivered_by, delivered_at, returned_by, returned_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, bookId);
            ps.setInt(2, randomOf(studentIds));
            ps.setString(3, start.toString());
            ps.setString(4, end.toString());
            ps.setString(5, status);
            ps.setString(6, requestedAt);
            if (approvedish && employeeId != null) {
                ps.setInt(7, employeeId);
            } else {
                ps.setNull(7, java.sql.Types.INTEGER);
            }
            if (deliveredish && employeeId != null) {
                ps.setInt(8, employeeId);
                ps.setString(9, start + " 10:30:00");
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
                ps.setNull(9, java.sql.Types.VARCHAR);
            }
            ps.setNull(10, java.sql.Types.INTEGER);
            ps.setNull(11, java.sql.Types.VARCHAR);
            ps.executeUpdate();
            return lastId(ps, conn);
        }
    }

    private static List<Integer> studentIds(Connection conn) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM users WHERE role = 'student'")) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    private static void updateLoanReturn(Connection conn, int loanId, LocalDate returnDay, int employeeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE loans SET returned_at = ?, returned_by = ? WHERE id = ?")) {
            ps.setString(1, returnDay + " 14:00:00");
            ps.setInt(2, employeeId);
            ps.setInt(3, loanId);
            ps.executeUpdate();
        }
    }

    private static void setBookAvailable(Connection conn, int bookId, boolean available) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE books SET is_available = ? WHERE id = ?")) {
            ps.setInt(1, available ? 1 : 0);
            ps.setInt(2, bookId);
            ps.executeUpdate();
        }
    }

    private static int scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int lastId(PreparedStatement ps, Connection conn) throws SQLException {
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

    private static <T> T randomOf(List<T> values) {
        return values.get(RANDOM.nextInt(values.size()));
    }

    private static LocalDate randomDateBetween(LocalDate start, LocalDate end) {
        long days = end.toEpochDay() - start.toEpochDay();
        return start.plusDays(RANDOM.nextInt((int) days + 1));
    }
}
