"""Seed the library database with realistic random data.

Generates: 3 employees (in addition to the manager), 20 students,
20 books and 20 loans in mixed statuses (pending / approved / borrowed /
returned, some of them late) so that every report has data to show.

Run:  python seed_data.py
Note: it deletes the existing library.db and creates a fresh one.
"""

import random
import sqlite3
import sys
import os
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import database
from auth import hash_password
import services as svc

random.seed()  # different data on every run

FIRST_NAMES = [
    "Ali", "Sara", "Reza", "Maryam", "Hossein", "Zahra", "Amir", "Neda",
    "Mohammad", "Fateme", "Saeed", "Parisa", "Mahdi", "Niloofar", "Kaveh",
    "Shirin", "Hamed", "Leyla", "Omid", "Sahar",
]
LAST_NAMES = [
    "Ahmadi", "Karimi", "Hosseini", "Moradi", "Rahimi", "Ghorbani",
    "Sadeghi", "Ebrahimi", "Nazari", "Jafari",
]
TITLES = [
    "Introduction to Algorithms", "Software Engineering", "Database Systems",
    "Operating System Concepts", "Computer Networks", "Artificial Intelligence",
    "Clean Code", "Design Patterns", "Discrete Mathematics", "Linear Algebra",
    "Calculus", "Data Structures in Python", "Machine Learning Basics",
    "Compiler Design", "Computer Architecture", "Statistics for Engineers",
    "Web Development with Flask", "Cryptography and Security",
    "Theory of Computation", "Graph Theory",
]
AUTHORS = [
    "Cormen et al.", "Sommerville", "Elmasri & Navathe", "Silberschatz",
    "Tanenbaum", "Russell & Norvig", "Robert C. Martin", "GoF",
    "Rosen", "Strang", "Stewart", "Goodrich", "Mitchell",
    "Aho & Ullman", "Patterson & Hennessy", "Montgomery",
    "Grinberg", "Stallings", "Sipser", "Diestel",
]


def random_date_between(start: date, end: date) -> date:
    return date.fromordinal(random.randint(start.toordinal(), end.toordinal()))


def seed(db_path: str | None = None):
    # start from a fresh database
    if db_path is None:
        db_path = database.DB_PATH
    if os.path.exists(db_path):
        os.remove(db_path)
    database.init(db_path)
    conn = database.connect()

    today = svc.today()

    # --- manager (seeded automatically) + 3 employees
    manager = conn.execute(
        "SELECT id FROM users WHERE role = 'manager'"
    ).fetchone()
    employee_ids = []
    for i in range(1, 4):
        cur = conn.execute(
            "INSERT INTO users (username, password_hash, full_name, role) "
            "VALUES (?, ?, ?, 'employee')",
            (f"employee{i}", hash_password(f"emp{i}pass"), f"Employee {i}"),
        )
        employee_ids.append(cur.lastrowid)

    # --- 20 students: ali1..ali20 style usernames
    student_ids = []
    used = set()
    names = random.sample(FIRST_NAMES, 20)
    for i, first in enumerate(names, 1):
        username = f"{first.lower()}{i}"
        while username in used:
            username += str(random.randint(0, 9))
        used.add(username)
        full_name = f"{first} {random.choice(LAST_NAMES)}"
        cur = conn.execute(
            "INSERT INTO users (username, password_hash, full_name, role, is_active) "
            "VALUES (?, ?, ?, 'student', ?)",
            (username, hash_password("student123"), full_name, 1),
        )
        student_ids.append(cur.lastrowid)
    # a couple of disabled students to test rule 3-7
    for sid in random.sample(student_ids, 2):
        conn.execute("UPDATE users SET is_active = 0 WHERE id = ?", (sid,))

    # --- 20 books (one per title), registered by random employees
    book_ids = []
    for title, author in zip(TITLES, AUTHORS):
        year = random.randint(2005, 2025)
        cur = conn.execute(
            "INSERT INTO books (title, author, publish_year, is_available, created_by) "
            "VALUES (?, ?, ?, 1, ?)",
            (title, author, year, random.choice(employee_ids)),
        )
        book_ids.append(cur.lastrowid)

    # --- 20 loans in mixed statuses
    available_books = list(book_ids)
    random.shuffle(available_books)
    created = {"pending": 0, "approved": 0, "borrowed": 0, "returned": 0, "rejected": 0}

    def make_loan(book_id, status, start, end, employee_id):
        cur = conn.execute(
            "INSERT INTO loans (book_id, student_id, start_date, end_date, status, "
            "requested_at, approved_by, delivered_by, delivered_at, returned_by, returned_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                book_id, random.choice(student_ids), start.isoformat(),
                end.isoformat(), status,
                f"{start.isoformat()} {random.randint(8, 18):02d}:{random.randint(10, 59):02d}:00",
                employee_id if status in ("approved", "borrowed", "returned") else None,
                employee_id if status in ("borrowed", "returned") else None,
                f"{start.isoformat()} 10:30:00" if status in ("borrowed", "returned") else None,
                employee_id if status == "returned" else None,
                None,
            ),
        )
        created[status] += 1
        return cur.lastrowid

    # 5 returned loans: 3 of them late (returned after end_date)
    for i in range(5):
        book_id = available_books.pop()
        start = random_date_between(today - timedelta(days=90), today - timedelta(days=20))
        end = start + timedelta(days=random.randint(7, 20))
        loan_id = make_loan(book_id, "returned", start, end, random.choice(employee_ids))
        if i < 3:  # late return
            return_day = end + timedelta(days=random.randint(1, 15))
        else:      # on time
            return_day = random_date_between(start + timedelta(days=2), end)
        conn.execute(
            "UPDATE loans SET returned_at = ?, returned_by = ? WHERE id = ?",
            (f"{return_day.isoformat()} 14:00:00", random.choice(employee_ids), loan_id),
        )
        conn.execute("UPDATE books SET is_available = 0 WHERE id = ?", (book_id,))
        conn.execute("UPDATE books SET is_available = 1 WHERE id = ?", (book_id,))

    # 5 currently borrowed (on loan now)
    for i in range(5):
        book_id = available_books.pop()
        start = random_date_between(today - timedelta(days=15), today - timedelta(days=3))
        end = start + timedelta(days=random.randint(10, 30))
        make_loan(book_id, "borrowed", start, end, random.choice(employee_ids))
        conn.execute("UPDATE books SET is_available = 0 WHERE id = ?", (book_id,))

    # 4 approved (waiting for the student to pick the book up)
    for i in range(4):
        book_id = available_books.pop()
        start = today  # start = today keeps it approvable/deliverable
        end = start + timedelta(days=random.randint(10, 25))
        make_loan(book_id, "approved", start, end, random.choice(employee_ids))
        conn.execute("UPDATE books SET is_available = 0 WHERE id = ?", (book_id,))

    # 3 pending review by the employee (start = today or yesterday)
    for i in range(3):
        book_id = available_books.pop()
        start = today - timedelta(days=random.randint(0, 1))
        end = start + timedelta(days=random.randint(7, 20))
        make_loan(book_id, "pending", start, end, None)

    # 3 rejected requests (books stay available)
    for i in range(3):
        book_id = random.choice(book_ids)
        start = random_date_between(today - timedelta(days=30), today - timedelta(days=10))
        end = start + timedelta(days=10)
        make_loan(book_id, "rejected", start, end, None)

    conn.commit()

    counts = {
        "students": conn.execute(
            "SELECT COUNT(*) c FROM users WHERE role='student'").fetchone()["c"],
        "employees": conn.execute(
            "SELECT COUNT(*) c FROM users WHERE role='employee'").fetchone()["c"],
        "books": conn.execute("SELECT COUNT(*) c FROM books").fetchone()["c"],
        "loans": conn.execute("SELECT COUNT(*) c FROM loans").fetchone()["c"],
    }
    conn.close()

    print(f"Seeded database: {db_path}")
    print(f"  students : {counts['students']} (2 of them disabled)")
    print(f"  employees: {counts['employees']} (employee1..3, password emp1pass/emp2pass/emp3pass)")
    print(f"  manager  : 1 (admin / admin123)")
    print(f"  books    : {counts['books']} (password for all students: student123)")
    print(f"  loans    : {counts['loans']} -> {created} (3 of the returned ones are late)")
    print("Passwords: manager=admin123 | employees=emp1pass, emp2pass, emp3pass | students=student123")


if __name__ == "__main__":
    seed(sys.argv[1] if len(sys.argv) > 1 else None)
