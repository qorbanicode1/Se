"""Database layer: connection, schema and seed data (SQLite)."""

import os
import sqlite3

from auth import hash_password

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "library.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    UNIQUE NOT NULL,
    password_hash TEXT    NOT NULL,
    full_name     TEXT    DEFAULT '',
    role          TEXT    NOT NULL CHECK (role IN ('student', 'employee', 'manager')),
    is_active     INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS books (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title        TEXT    NOT NULL,
    author       TEXT    NOT NULL,
    publish_year INTEGER,
    is_available INTEGER NOT NULL DEFAULT 1,
    created_by   INTEGER REFERENCES users(id),
    created_at   TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS loans (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    book_id      INTEGER NOT NULL REFERENCES books(id),
    student_id   INTEGER NOT NULL REFERENCES users(id),
    start_date   TEXT    NOT NULL,
    end_date     TEXT    NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'approved', 'rejected', 'borrowed', 'returned')),
    requested_at TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    approved_by  INTEGER REFERENCES users(id),
    delivered_by INTEGER REFERENCES users(id),
    delivered_at TEXT,
    returned_by  INTEGER REFERENCES users(id),
    returned_at  TEXT
);
"""


def connect():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init(db_path=DB_PATH):
    global DB_PATH
    DB_PATH = db_path
    conn = connect()
    conn.executescript(SCHEMA)
    # Seed the default library manager account (boss) on first run.
    if not conn.execute(
        "SELECT 1 FROM users WHERE role = 'manager'"
    ).fetchone():
        conn.execute(
            "INSERT INTO users (username, password_hash, full_name, role) VALUES (?, ?, ?, 'manager')",
            ("admin", hash_password("admin123"), "Library Manager"),
        )
    conn.commit()
    conn.close()


if __name__ == "__main__":
    init()
    print(f"Database initialized at {DB_PATH}")
