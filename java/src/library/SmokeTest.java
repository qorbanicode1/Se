package library;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end smoke test of the library system (uses a temporary database).
 *
 * <p>Run: {@code java -cp "out;lib/sqlite-jdbc.jar" library.SmokeTest}</p>
 * <p>This mirrors {@code smoke_test.py} check by check.</p>
 */
public final class SmokeTest {

    private static final List<String> ERRORS = new ArrayList<>();

    private SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        File temp = File.createTempFile("library-smoke", ".db");
        Db.init(temp.getPath());
        try (Connection conn = Db.connect()) {
            run(conn);
        } finally {
            boolean deleted = temp.delete();
            if (!deleted) {
                temp.deleteOnExit();
            }
        }

        System.out.println();
        if (!ERRORS.isEmpty()) {
            System.out.println(ERRORS.size() + " FAILURE(S): " + ERRORS);
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    private static void run(Connection conn) throws SQLException {
        LocalDate today = Services.today();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate nextWeek = today.plusDays(7);

        // manager seeded
        User mgr = Services.login(conn, "admin", "admin123", "manager");
        check("manager default login", mgr != null);

        // 4-1 create employee
        Services.createEmployee(conn, mgr.id, "emp1", "emp123", "Employee One");
        User emp = Services.login(conn, "emp1", "emp123", "employee");
        check("employee login", emp != null);

        // 1-1 student register + login
        Services.registerStudent(conn, "ali", "pass123", "Ali Ahmadi");
        User stu = Services.login(conn, "ali", "pass123", "student");
        check("student register/login", stu != null && stu.active);

        // duplicate username rejected
        try {
            Services.registerStudent(conn, "ali", "x", "Dup");
            check("duplicate username rejected", false);
        } catch (IllegalArgumentException e) {
            check("duplicate username rejected", true);
        }

        // 3-3 employee adds books
        int b1 = Services.addBook(conn, emp.id, "Intro to Algorithms", "CLRS", 2009);
        int b2 = Services.addBook(conn, emp.id, "Software Engineering", "Sommerville", 2016);
        check("books added", b1 > 0 && b2 > 0);

        // 3-4 search & edit
        List<Book> rows = Services.searchBooks(conn, "software", null, 2016, true);
        check("search by title+year", rows.size() == 1 && rows.get(0).id == b2);
        rows = Services.searchBooks(conn, null, "CLRS", null, true);
        check("search by author", rows.size() == 1);

        // 1-3 student search shows availability
        rows = Services.searchBooks(conn, "Intro", null, null, true);
        check("student search shows availability", !rows.isEmpty() && rows.get(0).available);

        // 1-4 student requests a loan starting today
        int loan1 = Services.requestLoan(conn, stu.id, b1, today.toString(), nextWeek.toString());
        check("loan request created", loan1 > 0);

        // start date in the past is rejected
        try {
            Services.requestLoan(conn, stu.id, b2, yesterday.toString(), nextWeek.toString());
            check("past start date rejected", false);
        } catch (IllegalArgumentException e) {
            check("past start date rejected", true);
        }

        // 3-5 approval only for start = today or yesterday
        List<Loan> pending = Services.pendingRequestsForToday(conn);
        check("pending request visible to employee",
                pending.stream().anyMatch(r -> r.id == loan1));
        Services.approveRequest(conn, emp.id, loan1);
        check("request approved", statusOf(conn, loan1).equals("approved"));
        check("book marked unavailable after approval", !Services.getBook(conn, b1).available);

        // future-dated request cannot be approved yet
        int loanFuture = Services.requestLoan(conn, stu.id, b2, tomorrow.toString(), nextWeek.toString());
        try {
            Services.approveRequest(conn, emp.id, loanFuture);
            check("future request not approvable", false);
        } catch (IllegalArgumentException e) {
            check("future request not approvable", true);
        }

        // second request for the on-loan book is rejected
        try {
            Services.requestLoan(conn, stu.id, b1, today.toString(), nextWeek.toString());
            check("unavailable book not requestable", false);
        } catch (IllegalArgumentException e) {
            check("unavailable book not requestable", true);
        }

        // 3-8 delivery then return (backdate delivery to simulate a late return)
        Services.deliverBook(conn, emp.id, loan1);
        LocalDate backdated = yesterday.minusDays(4);
        execute(conn, "UPDATE loans SET delivered_at=? WHERE id=?",
                backdated + " 09:00:00", loan1);
        check("delivered", statusOf(conn, loan1).equals("borrowed"));

        // 3-6 student report mid-loan (loanFuture is still pending)
        Services.StudentLoanReport report = Services.studentLoanReport(conn, stu.id);
        check("student stats (not returned = 1)",
                report.stats.total == 2 && report.stats.notReturned == 1 && report.stats.late == 0);

        // simulate a LATE return: backdate end_date, then return
        LocalDate lateEnd = yesterday.minusDays(3);
        execute(conn, "UPDATE loans SET end_date=? WHERE id=?", lateEnd.toString(), loan1);
        Services.receiveReturn(conn, emp.id, loan1);
        check("returned", statusOf(conn, loan1).equals("returned"));
        check("book available again", Services.getBook(conn, b1).available);

        report = Services.studentLoanReport(conn, stu.id);
        check("late return detected", report.stats.late == 1);

        // 3-7 disable student -> cannot request
        Services.setStudentActive(conn, stu.id, false);
        try {
            Services.requestLoan(conn, stu.id, b2, today.toString(), nextWeek.toString());
            check("disabled student cannot borrow", false);
        } catch (IllegalArgumentException e) {
            check("disabled student cannot borrow", true);
        }
        Services.setStudentActive(conn, stu.id, true);

        // 3-2 change password
        Services.changePassword(conn, stu.id, "pass123", "newpass");
        check("password changed", Services.login(conn, "ali", "newpass", null) != null);

        // 2- guest views
        check("guest student count", Services.studentCount(conn) == 1);
        List<Book> guestRows = Services.searchBooks(conn, "Algorithms", null, null, false);
        // the availability column is not selected at all, so the flag stays false
        // even though the book itself is available
        check("guest title search (no availability column)",
                !guestRows.isEmpty() && !guestRows.get(0).available && Services.getBook(conn, b1).available);
        Services.SimpleStats s = Services.simpleStats(conn);
        check("guest stats", s.students == 1 && s.books == 2 && s.loans >= 1);

        // 4-2 employee performance
        Services.EmployeePerformance p = Services.employeePerformance(conn, emp.id);
        check("employee performance", p.booksRegistered == 2
                && p.loansDelivered == 1 && p.returnsReceived == 1);

        // 4-3 loan statistics
        Services.LoanStatistics ls = Services.loanStatistics(conn);
        check("loan statistics", ls.totalRequests == 2 && ls.totalDelivered == 1
                && ls.avgLoanDays >= 3);

        // 4-4 all students report + top late
        Services.AllStudentsReport all = Services.allStudentsReport(conn);
        check("all-students report", !all.reports.isEmpty() && all.reports.get(0).stats.late == 1);
        check("top-late includes ali",
                !all.topLate.isEmpty() && "ali".equals(all.topLate.get(0).student.username));
    }

    // --------------------------------------------------------------- internals

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS" : "FAIL") + ": " + name);
        if (!condition) {
            ERRORS.add(name);
        }
    }

    private static String statusOf(Connection conn, int loanId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM loans WHERE id = ?")) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void execute(Connection conn, String sql, String value, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
        conn.commit();
    }
}
