"""End-to-end smoke test of the library system (uses a temporary database)."""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import database
import services as svc
from auth import hash_password

tmp = tempfile.mktemp(suffix=".db")
database.init(tmp)
conn = database.connect()

errors = []


def check(name, condition):
    print(f"{'PASS' if condition else 'FAIL'}: {name}")
    if not condition:
        errors.append(name)


today = svc.today()
yesterday = today.fromordinal(today.toordinal() - 1)
tomorrow = today.fromordinal(today.toordinal() + 1)
next_week = today.fromordinal(today.toordinal() + 7)

# manager seeded
mgr = svc.login(conn, "admin", "admin123", role="manager")
check("manager default login", mgr is not None)

# 4-1 create employee
svc.create_employee(conn, mgr["id"], "emp1", "emp123", "Employee One")
emp = svc.login(conn, "emp1", "emp123", role="employee")
check("employee login", emp is not None)

# 1-1 student register + login
svc.register_student(conn, "ali", "pass123", "Ali Ahmadi")
stu = svc.login(conn, "ali", "pass123", role="student")
check("student register/login", stu is not None and stu["is_active"] == 1)

# duplicate username rejected
try:
    svc.register_student(conn, "ali", "x", "Dup")
    check("duplicate username rejected", False)
except ValueError:
    check("duplicate username rejected", True)

# 3-3 employee adds books
b1 = svc.add_book(conn, emp["id"], "Intro to Algorithms", "CLRS", 2009)
b2 = svc.add_book(conn, emp["id"], "Software Engineering", "Sommerville", 2016)
check("books added", b1 and b2)

# 3-4 search & edit
rows = svc.search_books(conn, title="software", publish_year=2016)
check("search by title+year", len(rows) == 1 and rows[0]["id"] == b2)
rows = svc.search_books(conn, author="CLRS")
check("search by author", len(rows) == 1)

# 1-3 student search shows availability
rows = svc.search_books(conn, title="Intro")
check("student search shows availability",
      rows and rows[0]["is_available"] == 1)

# 1-4 student requests a loan starting today
loan1 = svc.request_loan(conn, stu["id"], b1, today.isoformat(), next_week.isoformat())
check("loan request created", loan1 > 0)

# start date in the past is rejected
try:
    svc.request_loan(conn, stu["id"], b2, yesterday.isoformat(), next_week.isoformat())
    check("past start date rejected", False)
except ValueError:
    check("past start date rejected", True)

# 3-5 approval only for start = today or yesterday
pending = svc.pending_requests_for_today(conn)
check("pending request visible to employee",
      any(r["id"] == loan1 for r in pending))
svc.approve_request(conn, emp["id"], loan1)
check("request approved", conn.execute(
    "SELECT status FROM loans WHERE id=?", (loan1,)).fetchone()[0] == "approved")
check("book marked unavailable after approval",
      svc.get_book(conn, b1)["is_available"] == 0)

# future-dated request cannot be approved yet
loan_future = svc.request_loan(conn, stu["id"], b2, tomorrow.isoformat(), next_week.isoformat())
try:
    svc.approve_request(conn, emp["id"], loan_future)
    check("future request not approvable", False)
except ValueError:
    check("future request not approvable", True)

# second request for the on-loan book is rejected
try:
    svc.request_loan(conn, stu["id"], b1, today.isoformat(), next_week.isoformat())
    check("unavailable book not requestable", False)
except ValueError:
    check("unavailable book not requestable", True)

# 3-8 delivery then return (backdate delivery to simulate a late return)
svc.deliver_book(conn, emp["id"], loan1)
backdated = yesterday.fromordinal(yesterday.toordinal() - 4)
conn.execute("UPDATE loans SET delivered_at=? WHERE id=?",
             (backdated.strftime("%Y-%m-%d %H:%M:%S"), loan1))
conn.commit()
check("delivered", conn.execute(
    "SELECT status FROM loans WHERE id=?", (loan1,)).fetchone()[0] == "borrowed")

# 3-6 student report mid-loan (loan_future is still pending)
loans, stats = svc.student_loan_report(conn, stu["id"])
check("student stats (not returned = 1)",
      stats["total"] == 2 and stats["not_returned"] == 1 and stats["late"] == 0)

# simulate a LATE return: backdate end_date, then return
late_end = yesterday.fromordinal(yesterday.toordinal() - 3).isoformat()
conn.execute("UPDATE loans SET end_date=? WHERE id=?", (late_end, loan1))
conn.commit()
svc.receive_return(conn, emp["id"], loan1)
check("returned", conn.execute(
    "SELECT status FROM loans WHERE id=?", (loan1,)).fetchone()[0] == "returned")
check("book available again", svc.get_book(conn, b1)["is_available"] == 1)

loans, stats = svc.student_loan_report(conn, stu["id"])
check("late return detected", stats["late"] == 1)

# 3-7 disable student -> cannot request
svc.set_student_active(conn, stu["id"], False)
try:
    svc.request_loan(conn, stu["id"], b2, today.isoformat(), next_week.isoformat())
    check("disabled student cannot borrow", False)
except ValueError:
    check("disabled student cannot borrow", True)
svc.set_student_active(conn, stu["id"], True)

# 3-2 change password
svc.change_password(conn, stu["id"], "pass123", "newpass")
check("password changed", svc.login(conn, "ali", "newpass") is not None)

# 2- guest views
check("guest student count", svc.student_count(conn) == 1)
rows = svc.search_books(conn, title="Algorithms", show_availability=False)
check("guest title search (no availability column)",
      rows and "is_available" not in rows[0].keys())
s = svc.simple_stats(conn)
check("guest stats", s["students"] == 1 and s["books"] == 2 and s["loans"] >= 1)

# 4-2 employee performance
p = svc.employee_performance(conn, emp["id"])
check("employee performance", p["books_registered"] == 2
      and p["loans_delivered"] == 1 and p["returns_received"] == 1)

# 4-3 loan statistics
ls = svc.loan_statistics(conn)
check("loan statistics", ls["total_requests"] == 2 and ls["total_delivered"] == 1
      and ls["avg_loan_days"] >= 3)

# 4-4 all students report + top late
reports, top = svc.all_students_report(conn)
check("all-students report", reports and reports[0]["stats"]["late"] == 1)
check("top-late includes ali", top and top[0]["student"]["username"] == "ali")

conn.close()
os.remove(tmp)
print()
if errors:
    print(f"{len(errors)} FAILURE(S): {errors}")
    sys.exit(1)
print("ALL TESTS PASSED")
