"""Dashboard server configuration."""
import base64
import json
import os
import secrets
from pathlib import Path

from cryptography.fernet import Fernet

# Paths
CONFIG_DIR = Path.home() / ".config" / "artemis"
CONFIG_DIR.mkdir(parents=True, exist_ok=True)

# Server
HOST = os.environ.get("ARTEMIS_DASHBOARD_HOST", "0.0.0.0")
PORT = int(os.environ.get("ARTEMIS_DASHBOARD_PORT", "5000"))
SECRET_KEY = os.environ.get("ARTEMIS_SECRET_KEY", "artemis-dev-secret-change-me")
SESSION_DURATION = 3600  # 1 hour

# Auth — single user, password from env or file
ADMIN_PASSWORD = os.environ.get("ARTEMIS_ADMIN_PASSWORD", None)
if not ADMIN_PASSWORD:
    password_file = CONFIG_DIR / "admin_password.txt"
    if password_file.exists():
        ADMIN_PASSWORD = password_file.read_text().strip()
    else:
        # Generate a random password and save it
        ADMIN_PASSWORD = secrets.token_urlsafe(16)
        password_file.write_text(ADMIN_PASSWORD)
        print(f"[config] Generated admin password: {ADMIN_PASSWORD}")

# Devices file
DEVICES_FILE = CONFIG_DIR / "devices.json"

# ---------------------------------------------------------------------------
# Token encryption at rest.
# The bearer token for each phone is a long-lived credential — never store it
# in plaintext. We encrypt the token field with a Fernet key kept in a
# 0600-perm file next to the registry.
# ---------------------------------------------------------------------------

KEY_FILE = CONFIG_DIR / "dashboard_store.key"

_fernet = None


def _get_fernet() -> Fernet:
    global _fernet
    if _fernet is not None:
        return _fernet
    if KEY_FILE.exists():
        key = KEY_FILE.read_bytes().strip()
    else:
        key = Fernet.generate_key()
        KEY_FILE.write_bytes(key)
        os.chmod(KEY_FILE, 0o600)
    _fernet = Fernet(key)
    return _fernet


def encrypt_token(plain: str) -> str:
    """Encrypt a token value; returns 'enc:<base64>'."""
    if not plain:
        return ""
    return "enc:" + _get_fernet().encrypt(plain.encode()).decode()


def decrypt_token(blob: str) -> str:
    """Decrypt a stored token; plaintext values pass through (legacy)."""
    if not blob:
        return ""
    if blob.startswith("enc:"):
        try:
            return _get_fernet().decrypt(blob[4:].encode()).decode()
        except Exception:
            return ""  # undecryptable — treat as absent, re-pair will refresh it
    return blob  # legacy plaintext


def load_devices():
    if DEVICES_FILE.exists():
        try:
            raw = json.loads(DEVICES_FILE.read_text())
        except Exception:
            raw = {}
        # Decrypt token fields on read
        for data in raw.values():
            if isinstance(data, dict) and data.get("token"):
                data["token"] = decrypt_token(data["token"])
        return raw
    return {}


def save_devices(devices):
    # Encrypt token fields on write — never persist a plaintext token.
    # Copy first: the input may be the live registry's __dict__ objects,
    # and mutating them in place would swap the working token for ciphertext.
    out = {}
    for key, data in devices.items():
        if isinstance(data, dict):
            copy = dict(data)
            if copy.get("token"):
                copy["token"] = encrypt_token(copy["token"])
            out[key] = copy
        else:
            out[key] = data
    DEVICES_FILE.write_text(json.dumps(out, indent=2))
