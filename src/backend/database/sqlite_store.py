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

_CREATE_RECENT_APPS = """
CREATE TABLE IF NOT EXISTS recent_apps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    icon        TEXT    NOT NULL DEFAULT 'category',
    color       TEXT    NOT NULL DEFAULT '#757575',
    updated_at  REAL    NOT NULL
);
"""

_CREATE_RECENT_TABS = """
CREATE TABLE IF NOT EXISTS recent_tabs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT    NOT NULL,
    url         TEXT    NOT NULL,
    link        TEXT    NOT NULL,
    updated_at  REAL    NOT NULL
);
"""

_CREATE_CONTEXT_SNAPSHOTS = """
CREATE TABLE IF NOT EXISTS context_snapshots (
    session_id  TEXT    PRIMARY KEY,
    payload     TEXT    NOT NULL,
    updated_at  REAL    NOT NULL
);
"""

_INDICES = [
    "CREATE INDEX IF NOT EXISTS idx_kpi_user     ON kpi_logs (user_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS idx_kpi_reaction ON kpi_logs (user_reaction);",
    "CREATE INDEX IF NOT EXISTS idx_sess_session ON session_events (session_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS idx_stress_user  ON stress_history (user_id, timestamp);",
    "CREATE INDEX IF NOT EXISTS idx_recent_apps_name ON recent_apps (name);",
    "CREATE INDEX IF NOT EXISTS idx_recent_tabs_link ON recent_tabs (link);",
]


class SQLiteStore:
    """Thread-safe SQLite wrapper using row_factory for dict-like access."""

    def __init__(self, db_path: str = "data/friday.db"):
        import base64
        import hashlib
        from cryptography.fernet import Fernet
        from config import settings

        self._path = str(Path(db_path).expanduser())
        Path(self._path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self._path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.execute("PRAGMA foreign_keys=ON;")
        self._init_schema()

        # Derive a stable 32-byte Fernet key from settings.HMAC_SECRET
        key_bytes = hashlib.sha256(settings.HMAC_SECRET.encode()).digest()
        fernet_key = base64.urlsafe_b64encode(key_bytes)
        self._fernet = Fernet(fernet_key)

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
            + _CREATE_RECENT_APPS
            + _CREATE_RECENT_TABS
            + _CREATE_CONTEXT_SNAPSHOTS
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

    # ──────────────────────────────────────────────────────────────────────────────
    # Persistent state: recent apps
    # ──────────────────────────────────────────────────────────────────────────────

    def upsert_recent_app(self, name: str, icon: str, color: str, timestamp: float):
        """Insert or update a recent app entry. Keeps at most 4 entries."""
        self._conn.execute(
            "DELETE FROM recent_apps WHERE name = ?", (name,)
        )
        self._conn.execute(
            "INSERT INTO recent_apps (name, icon, color, updated_at) VALUES (?, ?, ?, ?)",
            (name, icon, color, timestamp),
        )
        # Prune to latest 4
        self._conn.execute(
            "DELETE FROM recent_apps WHERE id NOT IN "
            "(SELECT id FROM recent_apps ORDER BY updated_at DESC LIMIT 4)"
        )
        self._conn.commit()

    def get_recent_apps(self, limit: int = 4) -> list[dict]:
        """Return recent apps ordered by most recent first."""
        cur = self._conn.execute(
            "SELECT name, icon, color, updated_at FROM recent_apps ORDER BY updated_at DESC LIMIT ?",
            (limit,),
        )
        return [{"name": r[0], "icon": r[1], "color": r[2], "timestamp": r[3]} for r in cur.fetchall()]

    # ──────────────────────────────────────────────────────────────────────────────
    # Persistent state: recent tabs
    # ──────────────────────────────────────────────────────────────────────────────

    def upsert_recent_tab(self, title: str, url: str, link: str, timestamp: float):
        """Insert or update a recent tab entry. Keeps at most 3 entries."""
        self._conn.execute(
            "DELETE FROM recent_tabs WHERE link = ?", (link,)
        )
        self._conn.execute(
            "INSERT INTO recent_tabs (title, url, link, updated_at) VALUES (?, ?, ?, ?)",
            (title, url, link, timestamp),
        )
        # Prune to latest 3
        self._conn.execute(
            "DELETE FROM recent_tabs WHERE id NOT IN "
            "(SELECT id FROM recent_tabs ORDER BY updated_at DESC LIMIT 3)"
        )
        self._conn.commit()

    def get_recent_tabs(self, limit: int = 3) -> list[dict]:
        """Return recent tabs ordered by most recent first."""
        cur = self._conn.execute(
            "SELECT title, url, link, updated_at FROM recent_tabs ORDER BY updated_at DESC LIMIT ?",
            (limit,),
        )
        return [{"title": r[0], "url": r[1], "link": r[2], "timestamp": r[3]} for r in cur.fetchall()]

    # ──────────────────────────────────────────────────────────────────────────────
    # Persistent state: context snapshots
    # ──────────────────────────────────────────────────────────────────────────────

    def _decrypt_payload(self, db_payload: Optional[str]) -> Optional[str]:
        if not db_payload:
            return None
        if not db_payload.startswith("[ENC]"):
            return db_payload
        try:
            encrypted_data = db_payload[5:]
            decrypted_bytes = self._fernet.decrypt(encrypted_data.encode())
            return decrypted_bytes.decode()
        except Exception as e:
            logger.error(f"Failed to decrypt context snapshot: {e}")
            return db_payload

    def save_context_snapshot(self, session_id: str, payload_json: str, timestamp: float):
        """Upsert the latest context object for a session, encrypting the payload."""
        try:
            encrypted_payload = self._fernet.encrypt(payload_json.encode()).decode()
            db_payload = f"[ENC]{encrypted_payload}"
        except Exception as e:
            logger.error(f"Failed to encrypt context snapshot: {e}")
            db_payload = payload_json

        self._conn.execute(
            "INSERT OR REPLACE INTO context_snapshots (session_id, payload, updated_at) VALUES (?, ?, ?)",
            (session_id, db_payload, timestamp),
        )
        self._conn.commit()

    def get_context_snapshot(self, session_id: str) -> Optional[str]:
        """Return the latest context JSON for a session, decrypting if necessary."""
        cur = self._conn.execute(
            "SELECT payload FROM context_snapshots WHERE session_id = ?",
            (session_id,),
        )
        row = cur.fetchone()
        return self._decrypt_payload(row[0]) if row else None

    def get_latest_context_snapshot(self) -> Optional[str]:
        """Return the most recently updated context snapshot across all sessions, decrypting if necessary."""
        cur = self._conn.execute(
            "SELECT payload FROM context_snapshots ORDER BY updated_at DESC LIMIT 1"
        )
        row = cur.fetchone()
        return self._decrypt_payload(row[0]) if row else None

    # ──────────────────────────────────────────────────────────────────────────────
    # Utilities
    # ──────────────────────────────────────────────────────────────────────────────

    def close(self):
        self._conn.close()
        logger.info("SQLiteStore connection closed.")
