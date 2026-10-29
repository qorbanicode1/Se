package library;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Console input/output helpers, mirroring the helpers in {@code main.py}. */
final class Console {

    private static final BufferedReader IN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Console() {
    }

    /** Thrown when stdin is closed (Ctrl-Z / piped input ended), like Python's EOFError. */
    static final class EofException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static String ask(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            String line = IN.readLine();
            if (line == null) {
                throw new EofException();
            }
            return line.trim();
        } catch (IOException e) {
            throw new EofException();
        }
    }

    static Integer askInt(String prompt) {
        String text = ask(prompt);
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            System.out.println("  Please enter a valid number.");
            return null;
        }
    }

    static String askDate(String prompt) {
        String text = ask(prompt);
        if (Services.parseDate(text) == null) {
            System.out.println("  Invalid date. Use the YYYY-MM-DD format.");
            return null;
        }
        return text;
    }

    static void printLoans(List<Loan> loans) {
        if (loans.isEmpty()) {
            System.out.println("  (no records)");
            return;
        }
        for (Loan loan : loans) {
            System.out.println("  Loan #" + loan.id + " | book: " + loan.bookTitle + " | "
                    + loan.startDate + " -> " + loan.endDate + " | status: " + loan.status);
        }
    }
}
