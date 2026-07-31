"""Authentication — single admin user with session management."""
import time
import secrets
import hashlib
import hmac
from fastapi import Request, HTTPException, Response
from . import config

# In-memory session store
_sessions: dict[str, dict] = {}

def verify_password(password: str) -> bool:
    # Constant-time comparison — no timing side channel on the admin password.
    return hmac.compare_digest(password.encode(), config.ADMIN_PASSWORD.encode())

def create_session() -> str:
    token = secrets.token_urlsafe(32)
    _sessions[token] = {
        "created_at": time.time(),
        "expires_at": time.time() + config.SESSION_DURATION,
    }
    return token

def validate_session(token: str | None) -> bool:
    if not token or token not in _sessions:
        return False
    session = _sessions[token]
    if time.time() > session["expires_at"]:
        del _sessions[token]
        return False
    return True

def get_session_token(request: Request) -> str | None:
    return request.cookies.get("session_token")

async def require_login(request: Request):
    token = get_session_token(request)
    if not validate_session(token):
        raise HTTPException(status_code=401, detail="Not authenticated")
    return token

def logout(token: str):
    _sessions.pop(token, None)
