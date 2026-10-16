"""Business logic for the university library management system.

Roles and main flows:
- student  : register/login, search books, request a loan for a date range
- guest    : student count, title-only search, simple stats
- employee : manage books, approve/deliver/receive loans, student reports
- manager  : create employees, employee/loan/student statistics
"""

import sqlite3
from datetime import date, datetime

from auth import hash_password, verify_password
from database import connect

DATE_FMT = "%Y-%m-%d"


# ---------------------------------------------------------------- utilities

def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def today() -> date:
    return date.today()


def parse_date(text: str):
    """Return a date object or None if the input is invalid."""
    try:
        return datetime.strptime(text.strip(), DATE_FMT).date()
    except (ValueError, AttributeError):
        return None


# ------------------------------------------------------------------- users

def register_student(conn, username, password, full_name):
    """1-1 Student self-registration (active by default)."""
    if not username.strip() or not password:
        raise ValueError("Username and password are required.")
    if conn.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
        raise ValueError("This username is already taken.")
    cur = conn.execute(
        "INSERT INTO users (username, password_hash, full_name, role, is_active) "
        "VALUES (?, ?, ?, 'student', 1)",
        (username.strip(), hash_password(password), full_name.strip()),
    )
    conn.commit()
    return cur.lastrowid


def create_employee(conn, manager_id, username, password, full_name=""):
    """4-1 Manager creates a library employee account."""
    if not username.strip() or not password:
        raise ValueError("Username and password are required.")
    if conn.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
        raise ValueError("This username is already taken.")
    cur = conn.execute(
        "INSERT INTO users (username, password_hash, full_name, role, is_active) "
        "VALUES (?, ?, ?, 'employee', 1)",
        (username.strip(), hash_password(password), full_name.strip()),
    )
    conn.commit()
    return cur.lastrowid


def login(conn, username, password, role=None):
    """1-2 / 3-1 Login. Returns the user row or None."""
    row = conn.execute(
        "SELECT * FROM users WHERE username = ?", (username,)
    ).fetchone()
    if row is None or not verify_password(password, row["password_hash"]):
        return None
    if role is not None and row["role"] != role:
        return None
    return row


def change_password(conn, user_id, old_password, new_password):
    """3-2 Change password after verifying the old one."""
    row = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if row is None or not verify_password(old_password, row["password_hash"]):
        raise ValueError("Current password is incorrect.")
    if not new_password:
        raise ValueError("New password cannot be empty.")
    conn.execute(
        "UPDATE users SET password_hash = ? WHERE id = ?",
        (hash_password(new_password), user_id),
    )
    conn.commit()


def set_student_active(conn, student_id, active: bool):
    """3-7 Enable/disable a student (disabled students cannot borrow)."""
    row = conn.execute(
        "SELECT * FROM users WHERE id = ? AND role = 'student'", (student_id,)
    ).fetchone()
    if row is None:
        raise ValueError("Student not found.")
    conn.execute(
        "UPDATE users SET is_active = ? WHERE id = ?",
        (1 if active else 0, student_id),
    )
    conn.commit()


def list_students(conn):
    return conn.execute(
        "SELECT * FROM users WHERE role = 'student' ORDER BY username"
    ).fetchall()


def student_count(conn) -> int:
    return conn.execute(
        "SELECT COUNT(*) AS c FROM users WHERE role = 'student'"
    ).fetchone()["c"]


# ------------------------------------------------------------------- books

def add_book(conn, employee_id, title, author, publish_year):
    """3-3 Register a new book."""
    if not title.strip() or not author.strip():
        raise ValueError("Title and author are required.")
    if publish_year is not None and publish_year <= 0:
        raise ValueError("Publish year must be a positive number.")
    cur = conn.execute(
        "INSERT INTO books (title, author, publish_year, created_by) VALUES (?, ?, ?, ?)",
        (title.strip(), author.strip(), publish_year, employee_id),
    )
    conn.commit()
    return cur.lastrowid


def search_books(conn, title=None, author=None, publish_year=None,
                 show_availability=True):
    """1-3 / 2-2 Search books by a combination of title / author / year.

    Empty filters are ignored, so any subset can be combined.
    """
    conditions, params = [], []
    if title:
        conditions.append("title LIKE ?")
        params.append(f"%{title}%")
    if author:
        conditions.append("author LIKE ?")
        params.append(f"%{author}%")
    if publish_year is not None:
        conditions.append("publish_year = ?")
        params.append(publish_year)
    where = f"WHERE {' AND '.join(conditions)}" if conditions else ""
    cols = "id, title, author, publish_year" + (
        ", is_available" if show_availability else ""
    )
    return conn.execute(
        f"SELECT {cols} FROM books {where} ORDER BY title", params
    ).fetchall()


def get_book(conn, book_id):
    return conn.execute(
        "SELECT * FROM books WHERE id = ?", (book_id,)
    ).fetchone()


# ------------------------------------------------------------------- loans

def request_loan(conn, student_id, book_id, start_date, end_date):
    """1-4 Student requests a loan for a [start, end] date range."""
    student = conn.execute(
        "SELECT * FROM users WHERE id = ? AND role = 'student'", (student_id,)
    ).fetchone()
    if student is None:
        raise ValueError("Student not found.")
    if not student["is_active"]:
        raise ValueError("Your account is disabled; you cannot borrow books.")

    book = get_book(conn, book_id)
    if book is None:
        raise ValueError("Book not found.")
    if not book["is_available"]:
        raise ValueError("This book is currently on loan.")

    start, end = parse_date(start_date), parse_date(end_date)
    if start is None or end is None:
        raise ValueError(f"Dates must use the {DATE_FMT} format.")
    if end < start:
        raise ValueError("End date must be on or after the start date.")
    if start < today():
        raise ValueError("Start date cannot be in the past.")

    cur = conn.execute(
        "INSERT INTO loans (book_id, student_id, start_date, end_date, status) "
        "VALUES (?, ?, ?, ?, 'pending')",
        (book_id, student_id, start.isoformat(), end.isoformat()),
    )
    conn.commit()
    return cur.lastrowid


def pending_requests_for_today(conn):
    """3-5 Loan requests whose start date is today or yesterday."""
    yesterday = today().toordinal() - 1
    rows = conn.execute(
        "SELECT l.*, b.title AS book_title, u.username AS student_username "
        "FROM loans l "
        "JOIN books b ON b.id = l.book_id "
        "JOIN users u ON u.id = l.student_id "
        "WHERE l.status = 'pending'"
    ).fetchall()
    return [
        r for r in rows
        if date.fromisoformat(r["start_date"]).toordinal() in (yesterday, today().toordinal())
    ]


def approve_request(conn, employee_id, loan_id):
    """3-5 Approve a pending request and mark the book as on loan.

    Any other pending requests for the same book are rejected.
    """
    loan = conn.execute("SELECT * FROM loans WHERE id = ?", (loan_id,)).fetchone()
    if loan is None:
        raise ValueError("Loan request not found.")
    if loan["status"] != "pending":
        raise ValueError("This request has already been processed.")
    if date.fromisoformat(loan["start_date"]).toordinal() not in (
        today().toordinal() - 1, today().toordinal()
    ):
        raise ValueError("Only requests starting today or yesterday can be approved.")
    book = get_book(conn, loan["book_id"])
    if not book["is_available"]:
        raise ValueError("The book is no longer available.")

    conn.execute("UPDATE books SET is_available = 0 WHERE id = ?", (loan["book_id"],))
    conn.execute(
        "UPDATE loans SET status = 'approved', approved_by = ? WHERE id = ?",
        (employee_id, loan_id),
    )
    conn.execute(
        "UPDATE loans SET status = 'rejected' "
        "WHERE book_id = ? AND status = 'pending' AND id != ?",
        (loan["book_id"], loan_id),
    )
    conn.commit()


def reject_request(conn, loan_id):
    loan = conn.execute("SELECT * FROM loans WHERE id = ?", (loan_id,)).fetchone()
    if loan is None or loan["status"] != "pending":
        raise ValueError("Pending request not found.")
    conn.execute("UPDATE loans SET status = 'rejected' WHERE id = ?", (loan_id,))
    conn.commit()


def deliver_book(conn, employee_id, loan_id):
    """After approval the student picks the book up; employee records delivery."""
    loan = conn.execute("SELECT * FROM loans WHERE id = ?", (loan_id,)).fetchone()
    if loan is None or loan["status"] != "approved":
        raise ValueError("Only approved requests can be delivered.")
    conn.execute(
        "UPDATE loans SET status = 'borrowed', delivered_by = ?, delivered_at = ? "
        "WHERE id = ?",
        (employee_id, now_str(), loan_id),
    )
    conn.commit()


def receive_return(conn, employee_id, loan_id):
    """3-8 Receive a borrowed book back and record the return time."""
    loan = conn.execute("SELECT * FROM loans WHERE id = ?", (loan_id,)).fetchone()
    if loan is None or loan["status"] != "borrowed":
        raise ValueError("Only borrowed books can be received back.")
    conn.execute(
        "UPDATE loans SET status = 'returned', returned_by = ?, returned_at = ? "
        "WHERE id = ?",
        (employee_id, now_str(), loan_id),
    )
    conn.execute("UPDATE books SET is_available = 1 WHERE id = ?", (loan["book_id"],))
    conn.commit()


def loans_for_student(conn, student_id):
    return conn.execute(
        "SELECT l.*, b.title AS book_title, b.author AS book_author "
        "FROM loans l JOIN books b ON b.id = l.book_id "
        "WHERE l.student_id = ? ORDER BY l.requested_at DESC",
        (student_id,),
    ).fetchall()


def student_loan_report(conn, student_id):
    """3-6 History + stats for one student."""
    loans = loans_for_student(conn, student_id)
    stats = {
        "total": len(loans),
        "not_returned": sum(1 for l in loans if l["status"] == "borrowed"),
        "late": sum(1 for l in loans if is_late(l)),
    }
    return loans, stats


def is_late(loan) -> bool:
    """A loan is late if it was returned after its end date."""
    if loan["status"] != "returned" or loan["returned_at"] is None:
        return False
    return date.fromisoformat(loan["returned_at"][:10]) > date.fromisoformat(loan["end_date"])


def late_days(loan) -> int:
    if not is_late(loan):
        return 0
    delta = date.fromisoformat(loan["returned_at"][:10]) - date.fromisoformat(loan["end_date"])
    return delta.days


def borrowed_books(conn, limit=5):
    """2-3 Most recent books that are currently on loan."""
    return conn.execute(
        "SELECT l.delivered_at, l.end_date, b.title, b.author, u.username AS student "
        "FROM loans l "
        "JOIN books b ON b.id = l.book_id "
        "JOIN users u ON u.id = l.student_id "
        "WHERE l.status = 'borrowed' "
        "ORDER BY l.delivered_at DESC LIMIT ?",
        (limit,),
    ).fetchall()


def simple_stats(conn):
    """2-3 Guest statistics."""
    return {
        "students": student_count(conn),
        "books": conn.execute("SELECT COUNT(*) AS c FROM books").fetchone()["c"],
        "loans": conn.execute("SELECT COUNT(*) AS c FROM loans").fetchone()["c"],
        "borrowed": conn.execute(
            "SELECT COUNT(*) AS c FROM loans WHERE status = 'borrowed'"
        ).fetchone()["c"],
    }


# ------------------------------------------------------- manager reporting

def employee_performance(conn, employee_id):
    """4-2 Books registered / loans delivered / returns received by an employee."""
    def one(sql):
        return conn.execute(sql, (employee_id,)).fetchone()["c"]

    return {
        "books_registered": one("SELECT COUNT(*) AS c FROM books WHERE created_by = ?"),
        "loans_delivered": one(
            "SELECT COUNT(*) AS c FROM loans WHERE delivered_by = ?"
        ),
        "returns_received": one(
            "SELECT COUNT(*) AS c FROM loans WHERE returned_by = ?"
        ),
    }


def loan_statistics(conn):
    """4-3 Overall loan statistics."""
    total_requests = conn.execute("SELECT COUNT(*) AS c FROM loans").fetchone()["c"]
    total_delivered = conn.execute(
        "SELECT COUNT(*) AS c FROM loans WHERE delivered_at IS NOT NULL"
    ).fetchone()["c"]
    rows = conn.execute(
        "SELECT delivered_at, returned_at FROM loans "
        "WHERE status = 'returned' AND delivered_at IS NOT NULL AND returned_at IS NOT NULL"
    ).fetchall()
    durations = [
        (date.fromisoformat(r["returned_at"][:10]) - date.fromisoformat(r["delivered_at"][:10])).days
        for r in rows
    ]
    avg_days = sum(durations) / len(durations) if durations else 0.0
    return {
        "total_requests": total_requests,
        "total_delivered": total_delivered,
        "avg_loan_days": round(avg_days, 2),
    }


def all_students_report(conn):
    """4-4 Per-student stats (as 3-6) for every student + top-10 late students."""
    reports = []
    for s in list_students(conn):
        loans, stats = student_loan_report(conn, s["id"])
        late_days_total = sum(late_days(l) for l in loans)
        reports.append({
            "student": s,
            "stats": stats,
            "late_days": late_days_total,
        })
    top_late = sorted(reports, key=lambda r: r["late_days"], reverse=True)[:10]
    return reports, top_late
