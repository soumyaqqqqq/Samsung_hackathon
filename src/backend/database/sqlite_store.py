"""
FRIDAY - database/sqlite_store.py
KPI logging, session event tracking, and export for judge verification.
"""

from __future__ import annotations

import logging
import sqlite3
from pathlib import Path
from typing import Optional

logger = logging.getLogger("friday.db.sqlite")

# ──────────────────────────────────────────────────────────────────────────────
# DDL
# ──────────────────────────────────────────────────────────────────────────────

_CREATE_KPI_LOGS = """
CREATE TABLE IF NOT EXISTS kpi_logs (
    log_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp       DATETIME DEFAULT (datetime('now')),
    user_id         TEXT     NOT NULL,
    stress_score    REAL     NOT NULL,          -- 0–100
    suggested_action TEXT    NOT NULL,
    response_score  REAL     NOT NULL,          -- 0–100
    user_reaction   TEXT,                       -- 'helpful' / 'dismissed' / 'ignored' / NULL
    agent_type      TEXT     NOT NULL
);
"""

_CREATE_SESSION_EVENTS = """
CREATE TABLE IF NOT EXISTS session_events (
    event_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   DATETIME DEFAULT (datetime('now')),
    session_id  TEXT    NOT NULL,
    device_id   TEXT    NOT NULL,
    event_type  TEXT    NOT NULL,    -- 'context_received' / 'response_sent'
    is_offline  INTEGER NOT NULL DEFAULT 0,   -- BOOLEAN (0/1)
    latency_ms  INTEGER NOT NULL DEFAULT 0
);
"""

_CREATE_STRESS_HISTORY = """
CREATE TABLE IF NOT EXISTS stress_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   DATETIME DEFAULT (datetime('now')),
    user_id     TEXT    NOT NULL,
    stress_score REAL   NOT NULL    -- 0–100
);
"""

_INDICES = [
    "CREATE INDEX IF NOT EXISTS idx_kpi_user     ON kpi_logs (user_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS idx_kpi_reaction ON kpi_logs (user_reaction);",
    "CREATE INDEX IF NOT EXISTS idx_sess_session ON session_events (session_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS idx_stress_user  ON stress_history (user_id, timestamp);",
]


class SQLiteStore:
    """Thread-safe SQLite wrapper using row_factory for dict-like access."""

    def __init__(self, db_path: str = "data/friday.db"):
        self._path = str(Path(db_path).expanduser())
        self._conn = sqlite3.connect(self._path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.execute("PRAGMA foreign_keys=ON;")
        self._init_schema()
        logger.info(f"SQLiteStore initialised: {self._path}")

    # ──────────────────────────────────────────────────────────────────────────
    # Schema init
    # ──────────────────────────────────────────────────────────────────────────

    def _init_schema(self):
        cur = self._conn.cursor()
        cur.executescript(
            _CREATE_KPI_LOGS
            + _CREATE_SESSION_EVENTS
            + _CREATE_STRESS_HISTORY
            + "".join(_INDICES)
        )
        self._conn.commit()

    # ──────────────────────────────────────────────────────────────────────────
    # KPI logs
    # ──────────────────────────────────────────────────────────────────────────

    def log_kpi(
        self,
        user_id: str,
        stress_score: float,
        suggested_action: str,
        response_score: float,
        user_reaction: Optional[str],
        agent_type: str,
    ) -> int:
        """Insert a KPI log row. Returns the new log_id."""
        cur = self._conn.execute(
            """
            INSERT INTO kpi_logs
                (user_id, stress_score, suggested_action, response_score, user_reaction, agent_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (user_id, stress_score, suggested_action, response_score, user_reaction, agent_type),
        )
        self._conn.commit()

        # Also persist to stress_history for longitudinal tracking
        self._conn.execute(
            "INSERT INTO stress_history (user_id, stress_score) VALUES (?, ?)",
            (user_id, stress_score),
        )
        self._conn.commit()

        return cur.lastrowid

    def update_reaction(self, log_id: int, reaction: str):
        """Update user_reaction for a previously logged decision."""
        self._conn.execute(
            "UPDATE kpi_logs SET user_reaction = ? WHERE log_id = ?",
            (reaction, log_id),
        )
        self._conn.commit()

    def export_logs(
        self,
        start_date: Optional[str] = None,
        limit: int = 1000,
    ) -> list[dict]:
        """Return kpi_logs rows as list of dicts."""
        query  = "SELECT * FROM kpi_logs"
        params: list = []

        if start_date:
            query  += " WHERE timestamp >= ?"
            params.append(start_date)

        query += f" ORDER BY timestamp DESC LIMIT {int(limit)}"
        cur = self._conn.execute(query, params)
        return [dict(row) for row in cur.fetchall()]

    def count_logs(self) -> int:
        row = self._conn.execute("SELECT COUNT(*) FROM kpi_logs").fetchone()
        return row[0] if row else 0

    # ──────────────────────────────────────────────────────────────────────────
    # Session events
    # ──────────────────────────────────────────────────────────────────────────

    def log_session_event(
        self,
        session_id: str,
        device_id: str,
        event_type: str,
        is_offline: bool,
        latency_ms: int,
    ):
        self._conn.execute(
            """
            INSERT INTO session_events
                (session_id, device_id, event_type, is_offline, latency_ms)
            VALUES (?, ?, ?, ?, ?)
            """,
            (session_id, device_id, event_type, int(is_offline), latency_ms),
        )
        self._conn.commit()

    # ──────────────────────────────────────────────────────────────────────────
    # Stress history
    # ──────────────────────────────────────────────────────────────────────────

    def recent_stress_logs(self, user_id: str, limit: int = 20) -> list[dict]:
        """Return the most recent stress scores for a user."""
        cur = self._conn.execute(
            """
            SELECT stress_score, timestamp
            FROM stress_history
            WHERE user_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
            """,
            (user_id, limit),
        )
        return [dict(row) for row in cur.fetchall()]

    # ──────────────────────────────────────────────────────────────────────────
    # Utilities
    # ──────────────────────────────────────────────────────────────────────────

    def close(self):
        self._conn.close()
        logger.info("SQLiteStore connection closed.")
