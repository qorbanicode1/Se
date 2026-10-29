package library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Command-line interface for the university library management system.
 *
 * <p>Run: {@code java -cp "out;lib/sqlite-jdbc.jar" library.App}</p>
 * <p>Default manager account: {@code admin / admin123}</p>
 */
public final class App {

    private App() {
    }

    // ------------------------------------------------------------------ guest

    /** 2- Guest user (no login). */
    private void guestMenu(Connection conn) throws SQLException {
        while (true) {
            System.out.println("\n=== Guest Menu ===");
            System.out.println("1. Registered students count");
            System.out.println("2. Search books by title");
            System.out.println("3. Simple statistics");
            System.out.println("0. Back");
            String choice = Console.ask("> ");
            if ("1".equals(choice)) {
                System.out.println("  Registered students: " + Services.studentCount(conn));
            } else if ("2".equals(choice)) {
                String title = Console.ask("  Book title (or part of it): ");
                List<Book> rows = Services.searchBooks(conn, title, null, null, false);
                if (rows.isEmpty()) {
                    System.out.println("  No books found.");
                }
                for (Book b : rows) {
                    System.out.println("  #" + b.id + " | " + b.title + " | " + b.author
                            + " | year: " + (b.publishYear == null ? "null" : b.publishYear));
                }
            } else if ("3".equals(choice)) {
                Services.SimpleStats s = Services.simpleStats(conn);
                System.out.println("  Total students: " + s.students);
                System.out.println("  Total books:    " + s.books);
                System.out.println("  Total loans:    " + s.loans);
                System.out.println("  Latest books currently on loan:");
                for (Loan b : Services.borrowedBooks(conn, 5)) {
                    System.out.println("    - " + b.bookTitle + " (" + b.bookAuthor
                            + ") | student: " + b.studentUsername + " | due: " + b.endDate);
                }
            } else if ("0".equals(choice)) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- student

    /** 1- Student features. */
    private void studentMenu(Connection conn, User user) throws SQLException {
        while (true) {
            System.out.println("\n=== Student Menu (" + user.username + ") ===");
            System.out.println("1. Search books");
            System.out.println("2. Request a book loan");
            System.out.println("3. My loan history");
            System.out.println("0. Logout");
            String choice = Console.ask("> ");
            if ("1".equals(choice)) {
                String title = Console.ask("  Title (enter to skip): ");
                String author = Console.ask("  Author (enter to skip): ");
                Integer year = Console.askInt("  Publish year (enter to skip): ");
                List<Book> rows = Services.searchBooks(conn,
                        title.isEmpty() ? null : title,
                        author.isEmpty() ? null : author,
                        year, true);
                if (rows.isEmpty()) {
                    System.out.println("  No books found.");
                }
                for (Book b : rows) {
                    String status = b.available ? "available" : "on loan";
                    System.out.println("  #" + b.id + " | " + b.title + " | " + b.author
                            + " | year: " + (b.publishYear == null ? "null" : b.publishYear)
                            + " | " + status);
                }
            } else if ("2".equals(choice)) {
                Integer bookId = Console.askInt("  Book id: ");
                if (bookId == null) {
                    continue;
                }
                String start = Console.askDate("  Start date (YYYY-MM-DD): ");
                if (start == null) {
                    continue;
                }
                String end = Console.askDate("  End date   (YYYY-MM-DD): ");
                if (end == null) {
                    continue;
                }
                try {
                    int loanId = Services.requestLoan(conn, user.id, bookId, start, end);
                    System.out.println("  Request #" + loanId + " submitted. Wait for employee approval.");
                } catch (IllegalArgumentException e) {
                    System.out.println("  Error: " + e.getMessage());
                }
            } else if ("3".equals(choice)) {
                Console.printLoans(Services.loansForStudent(conn, user.id));
            } else if ("0".equals(choice)) {
                return;
            }
        }
    }

    // --------------------------------------------------------------- employee

    /** 3- Library employee features. */
    private void employeeMenu(Connection conn, User user) throws SQLException {
        while (true) {
            System.out.println("\n=== Employee Menu (" + user.username + ") ===");
            System.out.println("1. Add a book");
            System.out.println("2. Search / edit a book");
            System.out.println("3. Pending loan requests (start = today or yesterday)");
            System.out.println("4. Deliver an approved book");
            System.out.println("5. Receive a returned book");
            System.out.println("6. Student loan report");
            System.out.println("7. Enable / disable a student");
            System.out.println("8. Change my password");
            System.out.println("0. Logout");
            String choice = Console.ask("> ");
            try {
                if ("1".equals(choice)) {
                    String title = Console.ask("  Title: ");
                    String author = Console.ask("  Author: ");
                    Integer year = Console.askInt("  Publish year (enter to skip): ");
                    int bookId = Services.addBook(conn, user.id, title, author, year);
                    System.out.println("  Book #" + bookId + " registered.");
                } else if ("2".equals(choice)) {
                    String title = Console.ask("  Title to search (enter for all): ");
                    List<Book> rows = Services.searchBooks(conn,
                            title.isEmpty() ? null : title, null, null, true);
                    for (Book b : rows) {
                        String status = b.available ? "available" : "on loan";
                        System.out.println("  #" + b.id + " | " + b.title + " | " + b.author
                                + " | year: " + (b.publishYear == null ? "null" : b.publishYear)
                                + " | " + status);
                    }
                    Integer bookId = Console.askInt("  Book id to edit (enter to cancel): ");
                    if (bookId == null) {
                        continue;
                    }
                    Book book = Services.getBook(conn, bookId);
                    if (book == null) {
                        System.out.println("  Book not found.");
                        continue;
                    }
                    String newTitle = Console.ask("  New title [" + book.title + "]: ");
                    if (newTitle.isEmpty()) {
                        newTitle = book.title;
                    }
                    String newAuthor = Console.ask("  New author [" + book.author + "]: ");
                    if (newAuthor.isEmpty()) {
                        newAuthor = book.author;
                    }
                    String yearText = Console.ask("  New year [" + book.publishYear + "]: ");
                    int newYear = yearText.isEmpty()
                            ? (book.publishYear == null ? 0 : book.publishYear)
                            : Integer.parseInt(yearText);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE books SET title = ?, author = ?, publish_year = ? WHERE id = ?")) {
                        ps.setString(1, newTitle);
                        ps.setString(2, newAuthor);
                        ps.setInt(3, newYear);
                        ps.setInt(4, bookId);
                        ps.executeUpdate();
                        conn.commit();
                    }
                    System.out.println("  Book updated.");
                } else if ("3".equals(choice)) {
                    List<Loan> requests = Services.pendingRequestsForToday(conn);
                    if (requests.isEmpty()) {
                        System.out.println("  No requests to review today.");
                    }
                    for (Loan r : requests) {
                        System.out.println("  Request #" + r.id + " | book: " + r.bookTitle
                                + " | student: " + r.studentUsername + " | "
                                + r.startDate + " -> " + r.endDate);
                    }
                    Integer loanId = Console.askInt("  Request id to approve/reject (enter to skip): ");
                    if (loanId == null) {
                        continue;
                    }
                    String action = Console.ask("  (a)pprove / (r)eject: ").toLowerCase();
                    if ("a".equals(action)) {
                        Services.approveRequest(conn, user.id, loanId);
                        System.out.println("  Request approved. The student can pick the book up.");
                    } else if ("r".equals(action)) {
                        Services.rejectRequest(conn, loanId);
                        System.out.println("  Request rejected.");
                    }
                } else if ("4".equals(choice)) {
                    Integer loanId = Console.askInt("  Approved request id: ");
                    Services.deliverBook(conn, user.id, loanId == null ? 0 : loanId);
                    System.out.println("  Book delivered to the student.");
                } else if ("5".equals(choice)) {
                    Integer loanId = Console.askInt("  Loan id to receive back: ");
                    Services.receiveReturn(conn, user.id, loanId == null ? 0 : loanId);
                    System.out.println("  Return recorded.");
                } else if ("6".equals(choice)) {
                    String username = Console.ask("  Student username: ");
                    Integer studentId = findUserId(conn, username, "student");
                    if (studentId == null) {
                        System.out.println("  Student not found.");
                        continue;
                    }
                    Services.StudentLoanReport report = Services.studentLoanReport(conn, studentId);
                    Console.printLoans(report.loans);
                    System.out.println("  Total loans: " + report.stats.total
                            + " | not returned: " + report.stats.notReturned
                            + " | late returns: " + report.stats.late);
                } else if ("7".equals(choice)) {
                    String username = Console.ask("  Student username: ");
                    Integer studentId = findUserId(conn, username, "student");
                    if (studentId == null) {
                        System.out.println("  Student not found.");
                        continue;
                    }
                    String action = Console.ask("  (e)nable / (d)isable: ").toLowerCase();
                    Services.setStudentActive(conn, studentId, "e".equals(action));
                    System.out.println("  Student status updated.");
                } else if ("8".equals(choice)) {
                    String old = Console.ask("  Current password: ");
                    String newPassword = Console.ask("  New password: ");
                    Services.changePassword(conn, user.id, old, newPassword);
                    System.out.println("  Password changed.");
                } else if ("0".equals(choice)) {
                    return;
                }
            } catch (IllegalArgumentException e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- manager

    /** 4- Library manager features. */
    private void managerMenu(Connection conn, User user) throws SQLException {
        while (true) {
            System.out.println("\n=== Manager Menu (" + user.username + ") ===");
            System.out.println("1. Create employee account");
            System.out.println("2. Employee performance report");
            System.out.println("3. Loan statistics");
            System.out.println("4. All students report + top-10 late students");
            System.out.println("0. Logout");
            String choice = Console.ask("> ");
            try {
                if ("1".equals(choice)) {
                    String username = Console.ask("  Employee username: ");
                    String password = Console.ask("  Employee password: ");
                    String fullName = Console.ask("  Full name (optional): ");
                    Services.createEmployee(conn, user.id, username, password, fullName);
                    System.out.println("  Employee account created.");
                } else if ("2".equals(choice)) {
                    String username = Console.ask("  Employee username: ");
                    Integer employeeId = findUserId(conn, username, "employee");
                    if (employeeId == null) {
                        System.out.println("  Employee not found.");
                        continue;
                    }
                    Services.EmployeePerformance p = Services.employeePerformance(conn, employeeId);
                    System.out.println("  Books registered: " + p.booksRegistered);
                    System.out.println("  Loans delivered:  " + p.loansDelivered);
                    System.out.println("  Returns received: " + p.returnsReceived);
                } else if ("3".equals(choice)) {
                    Services.LoanStatistics s = Services.loanStatistics(conn);
                    System.out.println("  Total loan requests: " + s.totalRequests);
                    System.out.println("  Total loans delivered: " + s.totalDelivered);
                    System.out.println("  Average loan duration (days): " + s.avgLoanDays);
                } else if ("4".equals(choice)) {
                    Services.AllStudentsReport all = Services.allStudentsReport(conn);
                    System.out.println("  -- Per-student statistics --");
                    for (Services.StudentReport r : all.reports) {
                        System.out.println("  " + r.student.username + ": loans=" + r.stats.total
                                + ", not returned=" + r.stats.notReturned
                                + ", late=" + r.stats.late
                                + ", late days=" + r.lateDays);
                    }
                    System.out.println("  -- Top-10 students by total late days --");
                    int i = 1;
                    for (Services.StudentReport r : all.topLate) {
                        System.out.println("  " + i + ". " + r.student.username
                                + " (" + r.lateDays + " late days)");
                        i++;
                    }
                } else if ("0".equals(choice)) {
                    return;
                }
            } catch (IllegalArgumentException e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------- main

    private void mainMenu(Connection conn) throws SQLException {
        while (true) {
            System.out.println("\n=== University Library Management System ===");
            System.out.println("1. Student login");
            System.out.println("2. Student register");
            System.out.println("3. Employee login");
            System.out.println("4. Manager login");
            System.out.println("5. Continue as guest");
            System.out.println("0. Exit");
            String choice = Console.ask("> ");
            if ("1".equals(choice)) {
                User user = Services.login(conn, Console.ask("  Username: "),
                        Console.ask("  Password: "), "student");
                if (user == null) {
                    System.out.println("  Invalid credentials.");
                } else if (!user.active) {
                    System.out.println("  Your account is disabled. Contact the library.");
                } else {
                    studentMenu(conn, user);
                }
            } else if ("2".equals(choice)) {
                String username = Console.ask("  Choose a username: ");
                String password = Console.ask("  Choose a password: ");
                String fullName = Console.ask("  Full name: ");
                try {
                    Services.registerStudent(conn, username, password, fullName);
                    System.out.println("  Registration successful. You can log in now.");
                } catch (IllegalArgumentException e) {
                    System.out.println("  Error: " + e.getMessage());
                }
            } else if ("3".equals(choice)) {
                User user = Services.login(conn, Console.ask("  Username: "),
                        Console.ask("  Password: "), "employee");
                if (user == null) {
                    System.out.println("  Invalid credentials.");
                } else {
                    employeeMenu(conn, user);
                }
            } else if ("4".equals(choice)) {
                User user = Services.login(conn, Console.ask("  Username: "),
                        Console.ask("  Password: "), "manager");
                if (user == null) {
                    System.out.println("  Invalid credentials.");
                } else {
                    managerMenu(conn, user);
                }
            } else if ("5".equals(choice)) {
                guestMenu(conn);
            } else if ("0".equals(choice)) {
                System.out.println("Goodbye!");
                return;
            }
        }
    }

    /** Same lookup as the inline SQL in main.py. */
    private static Integer findUserId(Connection conn, String username, String role) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE username = ? AND role = ?")) {
            ps.setString(1, username);
            ps.setString(2, role);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : null;
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            Db.init();
            try (Connection conn = Db.connect()) {
                new App().mainMenu(conn);
            }
        } catch (Console.EofException e) {
            System.out.println("\nGoodbye!");
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
