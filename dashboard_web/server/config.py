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
# The bearer/refresh tokens for each phone are credentials — never store them
# in plaintext. We encrypt with a Fernet key kept in a 0600-perm file next to
# the registry. The TLS cert pin is also encrypted: while the pin is not
# secret, encrypting it prevents an attacker with read-only file access from
# swapping in their own pin to enable MITM during the next refresh.
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


# Fields that must never be persisted in plaintext.
ENCRYPTED_FIELDS = ("token", "refresh_token", "cert_fp")


def load_devices():
    if DEVICES_FILE.exists():
        try:
            raw = json.loads(DEVICES_FILE.read_text())
        except Exception:
            raw = {}
        # Decrypt secret fields on read
        for data in raw.values():
            if isinstance(data, dict):
                for f in ENCRYPTED_FIELDS:
                    if data.get(f):
                        data[f] = decrypt_token(data[f])
        return raw
    return {}


def save_devices(devices):
    # Encrypt secret fields on write — never persist them in plaintext.
    # Copy first: the input may be the live registry's __dict__ objects,
    # and mutating them in place would swap the working token for ciphertext.
    out = {}
    for key, data in devices.items():
        if isinstance(data, dict):
            copy = dict(data)
            for f in ENCRYPTED_FIELDS:
                if copy.get(f):
                    copy[f] = encrypt_token(copy[f])
            out[key] = copy
        else:
            out[key] = data
    DEVICES_FILE.write_text(json.dumps(out, indent=2))
