"""Dashboard server configuration."""
import os
import json
from pathlib import Path

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
        import secrets
        ADMIN_PASSWORD = secrets.token_urlsafe(16)
        password_file.write_text(ADMIN_PASSWORD)
        print(f"[config] Generated admin password: {ADMIN_PASSWORD}")

# Devices file
DEVICES_FILE = CONFIG_DIR / "devices.json"

def load_devices():
    if DEVICES_FILE.exists():
        return json.loads(DEVICES_FILE.read_text())
    return {}

def save_devices(devices):
    DEVICES_FILE.write_text(json.dumps(devices, indent=2))
