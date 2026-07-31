"""SQLite-backed encrypted store for Artemis dashboard state.

Replaces the legacy JSON files (devices.json, tokens.json, known_hosts.json)
with a single database at ~/.config/artemis/artemis.db. The dashboard reads
and writes exclusively through this module — it never touches the phone app
to persist anything, so the DB works with the phone fully offline.

Secrets are Fernet-encrypted at rest (same key as before, dashboard_store.key):
  devices:      token, refresh_token, cert_fp, nickname
  media:        path, note
  cli_tokens:   token, refresh_token
  known_hosts:  fingerprint

Migration: on first access, any legacy JSON files present are imported and
then DELETED. The only files that remain in CONFIG_DIR are the Fernet key
(dashboard_store.key) and the admin password (admin_password.txt) — both are
credentials the DB itself needs and must never be removed.

Threading: one SQLite connection per call (uvicorn runs handlers on a thread
pool; connections are not shareable across threads). WAL mode keeps readers
and writers from blocking each other.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from typing import Any, Optional

from . import config

_ENC_FIELDS = {"token", "refresh_token", "cert_fp", "nickname", "path", "note", "fingerprint"}

_DEVICE_COLUMNS = [
    "key", "host", "port", "token", "refresh_token", "cert_fp",
    "device_id", "name", "nickname", "paired", "last_seen", "expires_at", "created_at",
]
_MEDIA_COLUMNS = ["id", "device_key", "kind", "path", "size_bytes",
                  "duration_sec", "captured_at", "note"]

_init_lock = threading.Lock()
_initialized = False


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(str(config.DB_FILE), timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _enc(value: Any) -> str:
    """Encrypt a plaintext field for storage ('' stays '')."""
    if not value:
        return ""
    return config.encrypt_token(str(value))


def _dec(value: Any) -> str:
    """Decrypt a stored field; legacy plaintext passes through."""
    if not value:
        return ""
    return config.decrypt_token(str(value))


def _init_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS devices (
            key           TEXT PRIMARY KEY,
            host          TEXT NOT NULL,
            port          INTEGER NOT NULL,
            token         TEXT DEFAULT '',
            refresh_token TEXT DEFAULT '',
            cert_fp       TEXT DEFAULT '',
            device_id     TEXT DEFAULT '',
            name          TEXT DEFAULT '',
            nickname      TEXT DEFAULT '',
            paired        INTEGER DEFAULT 0,
            last_seen     REAL DEFAULT 0,
            expires_at    REAL DEFAULT 0,
            created_at    REAL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS media (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            device_key   TEXT NOT NULL,
            kind         TEXT NOT NULL,
            path         TEXT DEFAULT '',
            size_bytes   INTEGER DEFAULT 0,
            duration_sec REAL DEFAULT 0,
            captured_at  REAL DEFAULT 0,
            note         TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS cli_tokens (
            host          TEXT PRIMARY KEY,
            token         TEXT DEFAULT '',
            refresh_token TEXT DEFAULT '',
            expires_at    REAL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS known_hosts (
            hp          TEXT PRIMARY KEY,
            fingerprint TEXT DEFAULT ''
        );
        """
    )


def _migrate_legacy() -> None:
    """Import legacy JSON files into the DB, then delete them.

    Values already carry the same Fernet encryption (or legacy plaintext),
    so they are stored verbatim — reads decrypt both forms.
    """
    # devices.json -> devices table
    if config.DEVICES_FILE.exists():
        try:
            raw = json.loads(config.DEVICES_FILE.read_text())
        except (json.JSONDecodeError, PermissionError, OSError):
            raw = {}
        conn = _connect()
        try:
            for key, data in raw.items():
                if not isinstance(data, dict):
                    continue
                row = {c: data.get(c, "") for c in _DEVICE_COLUMNS}
                row["key"] = key
                row["paired"] = 1 if data.get("paired") else 0
                row["created_at"] = data.get("created_at") or time.time()
                # Normalize: legacy plaintext is re-encrypted so nothing
                # sensitive ever sits on disk unencrypted.
                for f in ("token", "refresh_token", "cert_fp", "nickname"):
                    row[f] = _enc(_dec(row[f]))
                _insert_device_row(conn, row)
            conn.commit()
        finally:
            conn.close()
        try:
            config.DEVICES_FILE.unlink()
        except OSError:
            pass
        print(f"[db] migrated {len(raw)} device(s) from devices.json -> artemis.db")

    # tokens.json (CLI) -> cli_tokens table
    if config.TOKENS_FILE.exists():
        try:
            raw = json.loads(config.TOKENS_FILE.read_text())
        except (json.JSONDecodeError, PermissionError, OSError):
            raw = {}
        conn = _connect()
        try:
            for host, val in raw.items():
                if isinstance(val, dict):
                    conn.execute(
                        "INSERT OR REPLACE INTO cli_tokens (host, token, refresh_token, expires_at) VALUES (?,?,?,?)",
                        (host, _enc(_dec(val.get("token", ""))), _enc(_dec(val.get("refresh_token", ""))),
                         val.get("expires_at", 0.0)))
                else:  # legacy {host: "token-string"}
                    conn.execute(
                        "INSERT OR REPLACE INTO cli_tokens (host, token, refresh_token, expires_at) VALUES (?,?,?,?)",
                        (host, _enc(_dec(val)), "", 0.0))
            conn.commit()
        finally:
            conn.close()
        try:
            config.TOKENS_FILE.unlink()
        except OSError:
            pass
        print(f"[db] migrated {len(raw)} CLI token(s) from tokens.json -> artemis.db")

    # known_hosts.json (CLI TOFU pins) -> known_hosts table
    if config.KNOWN_HOSTS_FILE.exists():
        try:
            raw = json.loads(config.KNOWN_HOSTS_FILE.read_text())
        except (json.JSONDecodeError, PermissionError, OSError):
            raw = {}
        conn = _connect()
        try:
            for hp, pin in raw.items():
                conn.execute(
                    "INSERT OR REPLACE INTO known_hosts (hp, fingerprint) VALUES (?,?)",
                    (hp, _enc(_dec(pin))))
            conn.commit()
        finally:
            conn.close()
        try:
            config.KNOWN_HOSTS_FILE.unlink()
        except OSError:
            pass
        print(f"[db] migrated {len(raw)} known host pin(s) from known_hosts.json -> artemis.db")


def _ensure() -> None:
    global _initialized
    if _initialized:
        return
    with _init_lock:
        if _initialized:
            return
        conn = _connect()
        try:
            _init_schema(conn)
            conn.commit()
        finally:
            conn.close()
        _migrate_legacy()
        _initialized = True


# ---------------------------------------------------------------------------
# Devices
# ---------------------------------------------------------------------------

def _insert_device_row(conn: sqlite3.Connection, row: dict) -> None:
    conn.execute(
        "INSERT OR REPLACE INTO devices (key, host, port, token, refresh_token, cert_fp, "
        "device_id, name, nickname, paired, last_seen, expires_at, created_at) "
        "VALUES (:key,:host,:port,:token,:refresh_token,:cert_fp,:device_id,:name,:nickname,"
        ":paired,:last_seen,:expires_at,:created_at)",
        row,
    )


def _row_to_device(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["token"] = _dec(d.get("token"))
    d["refresh_token"] = _dec(d.get("refresh_token"))
    d["cert_fp"] = _dec(d.get("cert_fp"))
    d["nickname"] = _dec(d.get("nickname"))
    d["paired"] = bool(d.get("paired"))
    return d


def get_devices() -> dict[str, dict]:
    """All devices keyed by 'host:port' (secrets decrypted)."""
    _ensure()
    conn = _connect()
    try:
        rows = conn.execute("SELECT * FROM devices").fetchall()
        return {r["key"]: _row_to_device(r) for r in rows}
    finally:
        conn.close()


def get_device(key: str) -> Optional[dict]:
    _ensure()
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM devices WHERE key=?", (key,)).fetchone()
        return _row_to_device(row) if row else None
    finally:
        conn.close()


def put_device(data: dict) -> None:
    """Full upsert of a device record. `data` is copied before encrypting —
    the caller may pass live objects and must not be mutated."""
    _ensure()
    row = {c: data.get(c, "") for c in _DEVICE_COLUMNS}
    row["key"] = data.get("key") or f"{data.get('host', '')}:{data.get('port', 0)}"
    for f in ("token", "refresh_token", "cert_fp", "nickname"):
        row[f] = _enc(row[f])
    row["paired"] = 1 if data.get("paired") else 0
    row["created_at"] = data.get("created_at") or time.time()
    conn = _connect()
    try:
        _insert_device_row(conn, row)
        conn.commit()
    finally:
        conn.close()


def update_device(key: str, **fields) -> None:
    """Partial update. Only the given fields are changed (plaintext in,
    encrypted on write)."""
    _ensure()
    if not fields:
        return
    allowed = set(_DEVICE_COLUMNS) - {"key"}
    updates = {k: v for k, v in fields.items() if k in allowed}
    if not updates:
        return
    for f in ("token", "refresh_token", "cert_fp", "nickname"):
        if f in updates:
            updates[f] = _enc(updates[f])
    if "paired" in updates:
        updates["paired"] = 1 if updates["paired"] else 0
    sets = ", ".join(f"{k}=?" for k in updates)
    conn = _connect()
    try:
        conn.execute(f"UPDATE devices SET {sets} WHERE key=?", (*updates.values(), key))
        conn.commit()
    finally:
        conn.close()


def delete_device(key: str) -> None:
    _ensure()
    conn = _connect()
    try:
        conn.execute("DELETE FROM devices WHERE key=?", (key,))
        conn.execute("DELETE FROM media WHERE device_key=?", (key,))
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Media (call recordings, videos, screen recordings, screenshots, photos)
# ---------------------------------------------------------------------------

def list_media(device_key: str) -> list[dict]:
    _ensure()
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM media WHERE device_key=? ORDER BY captured_at DESC, id DESC",
            (device_key,)).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            d["path"] = _dec(d.get("path"))
            d["note"] = _dec(d.get("note"))
            out.append(d)
        return out
    finally:
        conn.close()


def get_media(media_id: int) -> dict | None:
    """Fetch a single media row (decrypted path/note) or None."""
    _ensure()
    conn = _connect()
    try:
        row = conn.execute(
            "SELECT * FROM media WHERE id=?", (media_id,)).fetchone()
        if not row:
            return None
        d = dict(row)
        d["path"] = _dec(d.get("path"))
        d["note"] = _dec(d.get("note"))
        return d
    finally:
        conn.close()


def add_media(device_key: str, kind: str, path: str = "", size_bytes: int = 0,
              duration_sec: float = 0.0, note: str = "",
              captured_at: Optional[float] = None) -> dict:
    _ensure()
    if kind not in ("call_recording", "video", "screen_recording", "screenshot",
                    "photo", "mic_recording"):
        raise ValueError(f"unknown media kind: {kind}")
    conn = _connect()
    try:
        cur = conn.execute(
            "INSERT INTO media (device_key, kind, path, size_bytes, duration_sec, captured_at, note) "
            "VALUES (?,?,?,?,?,?,?)",
            (device_key, kind, _enc(path), size_bytes, duration_sec,
             captured_at or time.time(), _enc(note)))
        conn.commit()
        media_id = cur.lastrowid
    finally:
        conn.close()
    for m in list_media(device_key):
        if m["id"] == media_id:
            return m
    return {"id": media_id}


def delete_media(media_id: int) -> None:
    _ensure()
    conn = _connect()
    try:
        conn.execute("DELETE FROM media WHERE id=?", (media_id,))
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# CLI tokens (replaces tokens.json)
# ---------------------------------------------------------------------------

def get_all_cli_tokens() -> dict[str, dict]:
    _ensure()
    conn = _connect()
    try:
        rows = conn.execute("SELECT * FROM cli_tokens").fetchall()
        return {
            r["host"]: {
                "token": _dec(r["token"]),
                "refresh_token": _dec(r["refresh_token"]),
                "expires_at": r["expires_at"],
            }
            for r in rows
        }
    finally:
        conn.close()


def set_cli_tokens(tokens: dict[str, dict]) -> None:
    _ensure()
    conn = _connect()
    try:
        for host, v in tokens.items():
            conn.execute(
                "INSERT OR REPLACE INTO cli_tokens (host, token, refresh_token, expires_at) "
                "VALUES (?,?,?,?)",
                (host, _enc(v.get("token", "")), _enc(v.get("refresh_token", "")),
                 v.get("expires_at", 0.0)))
        conn.commit()
    finally:
        conn.close()


def delete_cli_token(host: str) -> None:
    _ensure()
    conn = _connect()
    try:
        conn.execute("DELETE FROM cli_tokens WHERE host=?", (host,))
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Known hosts (CLI TOFU pins, replaces known_hosts.json)
# ---------------------------------------------------------------------------

def get_known_hosts() -> dict[str, str]:
    _ensure()
    conn = _connect()
    try:
        rows = conn.execute("SELECT * FROM known_hosts").fetchall()
        return {r["hp"]: _dec(r["fingerprint"]) for r in rows}
    finally:
        conn.close()


def replace_known_hosts(pins: dict[str, str]) -> None:
    _ensure()
    conn = _connect()
    try:
        conn.execute("DELETE FROM known_hosts")
        for hp, pin in pins.items():
            conn.execute(
                "INSERT OR REPLACE INTO known_hosts (hp, fingerprint) VALUES (?,?)",
                (hp, _enc(pin)))
        conn.commit()
    finally:
        conn.close()


def remember_pin(hp: str, pin: str) -> None:
    _ensure()
    conn = _connect()
    try:
        conn.execute(
            "INSERT OR REPLACE INTO known_hosts (hp, fingerprint) VALUES (?,?)",
            (hp, _enc(pin)))
        conn.commit()
    finally:
        conn.close()


def get_pin(hp: str) -> str:
    return get_known_hosts().get(hp, "")
