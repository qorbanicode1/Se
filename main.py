"""Command-line interface for the university library management system.

Run:  python main.py
Default manager account:  admin / admin123
"""

import sys
from datetime import timedelta

import services as svc
from database import connect, init


def ask(prompt):
    return input(prompt).strip()


def ask_int(prompt):
    text = ask(prompt)
    try:
        return int(text)
    except ValueError:
        print("  Please enter a valid number.")
        return None


def ask_date(prompt):
    text = ask(prompt)
    if svc.parse_date(text) is None:
        print(f"  Invalid date. Use the YYYY-MM-DD format.")
        return None
    return text


def print_loans(loans):
    if not loans:
        print("  (no records)")
        return
    for l in loans:
        print(
            f"  Loan #{l['id']} | book: {l['book_title']} | "
            f"{l['start_date']} -> {l['end_date']} | status: {l['status']}"
        )


# ------------------------------------------------------------------ guest

def guest_menu(conn):
    """2- Guest user (no login)."""
    while True:
        print("\n=== Guest Menu ===")
        print("1. Registered students count")
        print("2. Search books by title")
        print("3. Simple statistics")
        print("0. Back")
        choice = ask("> ")
        if choice == "1":
            print(f"  Registered students: {svc.student_count(conn)}")
        elif choice == "2":
            title = ask("  Book title (or part of it): ")
            rows = svc.search_books(conn, title=title, show_availability=False)
            if not rows:
                print("  No books found.")
            for b in rows:
                print(f"  #{b['id']} | {b['title']} | {b['author']} | year: {b['publish_year']}")
        elif choice == "3":
            s = svc.simple_stats(conn)
            print(f"  Total students: {s['students']}")
            print(f"  Total books:    {s['books']}")
            print(f"  Total loans:    {s['loans']}")
            print("  Latest books currently on loan:")
            for b in svc.borrowed_books(conn):
                print(f"    - {b['title']} ({b['author']}) | student: {b['student']} | due: {b['end_date']}")
        elif choice == "0":
            return


# ---------------------------------------------------------------- student

def student_menu(conn, user):
    """1- Student features."""
    while True:
        print(f"\n=== Student Menu ({user['username']}) ===")
        print("1. Search books")
        print("2. Request a book loan")
        print("3. My loan history")
        print("0. Logout")
        choice = ask("> ")
        if choice == "1":
            title = ask("  Title (enter to skip): ")
            author = ask("  Author (enter to skip): ")
            year = ask_int("  Publish year (enter to skip): ")
            rows = svc.search_books(conn, title=title or None,
                                    author=author or None, publish_year=year)
            if not rows:
                print("  No books found.")
            for b in rows:
                status = "available" if b["is_available"] else "on loan"
                print(f"  #{b['id']} | {b['title']} | {b['author']} | "
                      f"year: {b['publish_year']} | {status}")
        elif choice == "2":
            book_id = ask_int("  Book id: ")
            if book_id is None:
                continue
            start = ask_date("  Start date (YYYY-MM-DD): ")
            if start is None:
                continue
            end = ask_date("  End date   (YYYY-MM-DD): ")
            if end is None:
                continue
            try:
                loan_id = svc.request_loan(conn, user["id"], book_id, start, end)
                print(f"  Request #{loan_id} submitted. Wait for employee approval.")
            except ValueError as e:
                print(f"  Error: {e}")
        elif choice == "3":
            print_loans(svc.loans_for_student(conn, user["id"]))
        elif choice == "0":
            return


# --------------------------------------------------------------- employee

def employee_menu(conn, user):
    """3- Library employee features."""
    while True:
        print(f"\n=== Employee Menu ({user['username']}) ===")
        print("1. Add a book")
        print("2. Search / edit a book")
        print("3. Pending loan requests (start = today or yesterday)")
        print("4. Deliver an approved book")
        print("5. Receive a returned book")
        print("6. Student loan report")
        print("7. Enable / disable a student")
        print("8. Change my password")
        print("0. Logout")
        choice = ask("> ")
        try:
            if choice == "1":
                title = ask("  Title: ")
                author = ask("  Author: ")
                year = ask_int("  Publish year (enter to skip): ")
                book_id = svc.add_book(conn, user["id"], title, author, year)
                print(f"  Book #{book_id} registered.")
            elif choice == "2":
                title = ask("  Title to search (enter for all): ")
                rows = svc.search_books(conn, title=title or None)
                for b in rows:
                    status = "available" if b["is_available"] else "on loan"
                    print(f"  #{b['id']} | {b['title']} | {b['author']} | "
                          f"year: {b['publish_year']} | {status}")
                book_id = ask_int("  Book id to edit (enter to cancel): ")
                if book_id is None:
                    continue
                book = svc.get_book(conn, book_id)
                if book is None:
                    print("  Book not found.")
                    continue
                new_title = ask(f"  New title [{book['title']}]: ") or book["title"]
                new_author = ask(f"  New author [{book['author']}]: ") or book["author"]
                year_text = ask(f"  New year [{book['publish_year']}]: ")
                new_year = int(year_text) if year_text else book["publish_year"]
                conn.execute(
                    "UPDATE books SET title = ?, author = ?, publish_year = ? WHERE id = ?",
                    (new_title, new_author, new_year, book_id),
                )
                conn.commit()
                print("  Book updated.")
            elif choice == "3":
                requests = svc.pending_requests_for_today(conn)
                if not requests:
                    print("  No requests to review today.")
                for r in requests:
                    print(f"  Request #{r['id']} | book: {r['book_title']} | "
                          f"student: {r['student_username']} | "
                          f"{r['start_date']} -> {r['end_date']}")
                loan_id = ask_int("  Request id to approve/reject (enter to skip): ")
                if loan_id is None:
                    continue
                action = ask("  (a)pprove / (r)eject: ").lower()
                if action == "a":
                    svc.approve_request(conn, user["id"], loan_id)
                    print("  Request approved. The student can pick the book up.")
                elif action == "r":
                    svc.reject_request(conn, loan_id)
                    print("  Request rejected.")
            elif choice == "4":
                loan_id = ask_int("  Approved request id: ")
                svc.deliver_book(conn, user["id"], loan_id)
                print("  Book delivered to the student.")
            elif choice == "5":
                loan_id = ask_int("  Loan id to receive back: ")
                svc.receive_return(conn, user["id"], loan_id)
                print("  Return recorded.")
            elif choice == "6":
                username = ask("  Student username: ")
                row = conn.execute(
                    "SELECT id FROM users WHERE username = ? AND role = 'student'",
                    (username,),
                ).fetchone()
                if row is None:
                    print("  Student not found.")
                    continue
                loans, stats = svc.student_loan_report(conn, row["id"])
                print_loans(loans)
                print(f"  Total loans: {stats['total']} | "
                      f"not returned: {stats['not_returned']} | "
                      f"late returns: {stats['late']}")
            elif choice == "7":
                username = ask("  Student username: ")
                row = conn.execute(
                    "SELECT * FROM users WHERE username = ? AND role = 'student'",
                    (username,),
                ).fetchone()
                if row is None:
                    print("  Student not found.")
                    continue
                action = ask("  (e)nable / (d)isable: ").lower()
                svc.set_student_active(conn, row["id"], action == "e")
                print("  Student status updated.")
            elif choice == "8":
                old = ask("  Current password: ")
                new = ask("  New password: ")
                svc.change_password(conn, user["id"], old, new)
                print("  Password changed.")
            elif choice == "0":
                return
        except ValueError as e:
            print(f"  Error: {e}")


# ---------------------------------------------------------------- manager

def manager_menu(conn, user):
    """4- Library manager features."""
    while True:
        print(f"\n=== Manager Menu ({user['username']}) ===")
        print("1. Create employee account")
        print("2. Employee performance report")
        print("3. Loan statistics")
        print("4. All students report + top-10 late students")
        print("0. Logout")
        choice = ask("> ")
        try:
            if choice == "1":
                username = ask("  Employee username: ")
                password = ask("  Employee password: ")
                full_name = ask("  Full name (optional): ")
                svc.create_employee(conn, user["id"], username, password, full_name)
                print("  Employee account created.")
            elif choice == "2":
                username = ask("  Employee username: ")
                row = conn.execute(
                    "SELECT id FROM users WHERE username = ? AND role = 'employee'",
                    (username,),
                ).fetchone()
                if row is None:
                    print("  Employee not found.")
                    continue
                p = svc.employee_performance(conn, row["id"])
                print(f"  Books registered: {p['books_registered']}")
                print(f"  Loans delivered:  {p['loans_delivered']}")
                print(f"  Returns received: {p['returns_received']}")
            elif choice == "3":
                s = svc.loan_statistics(conn)
                print(f"  Total loan requests: {s['total_requests']}")
                print(f"  Total loans delivered: {s['total_delivered']}")
                print(f"  Average loan duration (days): {s['avg_loan_days']}")
            elif choice == "4":
                reports, top = svc.all_students_report(conn)
                print("  -- Per-student statistics --")
                for r in reports:
                    st = r["stats"]
                    print(f"  {r['student']['username']}: loans={st['total']}, "
                          f"not returned={st['not_returned']}, late={st['late']}, "
                          f"late days={r['late_days']}")
                print("  -- Top-10 students by total late days --")
                for i, r in enumerate(top, 1):
                    print(f"  {i}. {r['student']['username']} ({r['late_days']} late days)")
            elif choice == "0":
                return
        except ValueError as e:
            print(f"  Error: {e}")


# ------------------------------------------------------------------ main

def main_menu(conn):
    while True:
        print("\n=== University Library Management System ===")
        print("1. Student login")
        print("2. Student register")
        print("3. Employee login")
        print("4. Manager login")
        print("5. Continue as guest")
        print("0. Exit")
        choice = ask("> ")
        if choice == "1":
            user = svc.login(conn, ask("  Username: "), ask("  Password: "), role="student")
            if user is None:
                print("  Invalid credentials.")
            elif not user["is_active"]:
                print("  Your account is disabled. Contact the library.")
            else:
                student_menu(conn, user)
        elif choice == "2":
            username = ask("  Choose a username: ")
            password = ask("  Choose a password: ")
            full_name = ask("  Full name: ")
            try:
                svc.register_student(conn, username, password, full_name)
                print("  Registration successful. You can log in now.")
            except ValueError as e:
                print(f"  Error: {e}")
        elif choice == "3":
            user = svc.login(conn, ask("  Username: "), ask("  Password: "), role="employee")
            if user is None:
                print("  Invalid credentials.")
            else:
                employee_menu(conn, user)
        elif choice == "4":
            user = svc.login(conn, ask("  Username: "), ask("  Password: "), role="manager")
            if user is None:
                print("  Invalid credentials.")
            else:
                manager_menu(conn, user)
        elif choice == "5":
            guest_menu(conn)
        elif choice == "0":
            print("Goodbye!")
            return


def main():
    init()
    conn = connect()
    try:
        main_menu(conn)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print("\nGoodbye!")
        sys.exit(0)
