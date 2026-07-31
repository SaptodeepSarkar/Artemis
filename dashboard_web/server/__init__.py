"""FastAPI app — Artemis Web Dashboard backend."""
import socket
import time
import json
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException, Response, Depends
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from . import config, auth
from .auth import require_login
from .device_client import (
    health, pair, device_info, location_current, camera_list, camera_capture
)
from .device_manager import registry

# ---------- App ----------

app = FastAPI(title="Artemis Dashboard", version="1.5.0")
templates = Jinja2Templates(directory=Path(__file__).parent.parent / "templates")

# ---------- Models ----------

class LoginRequest(BaseModel):
    password: str

class PairRequest(BaseModel):
    host: str
    port: int = 8443
    code: str

class RefreshRequest(BaseModel):
    host: str
    port: int = 8443

class MediaRequest(BaseModel):
    kind: str
    path: str = ""
    size_bytes: int = 0
    duration_sec: float = 0.0
    note: str = ""

class NicknameRequest(BaseModel):
    nickname: str

# ---------- Helpers ----------

def _probe_online(dev) -> bool:
    """Quick reachability probe for the fleet UI (short timeout so an
    offline phone never stalls the page)."""
    try:
        info, _ = health(dev.host, dev.port, cert_fp=dev.cert_fp or None, timeout=1.5)
        return info.get("status") == "ok"
    except Exception:
        return False

def _paired_device_or_error(key: str):
    """Resolve a paired device, refreshing its access token before use.

    Returns (dev, None) on success, or (None, error_dict) with a
    machine-readable error when the trust relationship is gone.
    """
    dev = registry.get_device(key)
    if not dev:
        return None, {"error": "not_found", "message": "Device not found"}
    if not dev.paired:
        return None, {"error": "not_paired", "message": "Device is not paired"}
    ok, detail = registry.ensure_device_token(key)
    if not ok:
        if detail in ("replay_detected", "invalid_token", "cert_mismatch"):
            return None, {
                "error": detail,
                "message": "Trust relationship was deleted by the device — re-pair to continue.",
            }
        if detail == "no_refresh_token":
            return None, {
                "error": "no_refresh_token",
                "message": "Paired before refresh-token support — re-pair once to upgrade (UX unchanged afterwards).",
            }
        return None, {"error": "token_unavailable", "message": detail}
    return registry.get_device(key), None

# ---------- Auth Routes ----------

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    token = auth.get_session_token(request)
    if auth.validate_session(token):
        devices = registry.get_all()
        # Live reachability per device so the fleet UI can render
        # ONLINE / OFFLINE (offline is shown in black).
        for dev in devices.values():
            dev.online = _probe_online(dev) if dev.paired else True
        return templates.TemplateResponse(request, "devices.html", {"devices": devices})
    return templates.TemplateResponse(request, "login.html", {})

@app.get("/dashboard/{key:path}", response_class=HTMLResponse)
async def device_dashboard(request: Request, key: str, _=Depends(require_login)):
    return templates.TemplateResponse(request, "dashboard.html", {"device_key": key})

@app.get("/api/auth/status")
async def auth_status(request: Request):
    token = auth.get_session_token(request)
    return {"authenticated": auth.validate_session(token)}

@app.post("/api/auth/login")
async def login(req: LoginRequest, response: Response):
    if not auth.verify_password(req.password):
        raise HTTPException(status_code=401, detail="Wrong password")
    session = auth.create_session()
    response.set_cookie(
        key="session_token", value=session,
        httponly=True, max_age=config.SESSION_DURATION,
        samesite="lax",
    )
    return {"ok": True}

@app.post("/api/auth/logout")
async def logout(request: Request, response: Response):
    token = auth.get_session_token(request)
    auth.logout(token)
    response.delete_cookie("session_token")
    return {"ok": True}

# ---------- Device Routes ----------

@app.get("/api/devices")
async def list_devices(_=Depends(require_login)):
    return registry.get_all()

@app.post("/api/devices/pair")
async def pair_device(req: PairRequest, _=Depends(require_login)):
    # TOFU: no stored pin yet -> accept whatever cert the phone presents
    # and pin it. If a pin exists and mismatches, pair() refuses.
    key = f"{req.host}:{req.port}"
    dev = registry.get_device(key)
    known_pin = dev.cert_fp if dev else None
    result, pin = pair(req.host, req.code, req.port,
                       cert_fp=known_pin or None, name=socket.gethostname())
    if not result or "token" not in result:
        if result and result.get("error") == "cert_mismatch":
            return {"error": "cert_mismatch",
                    "message": "Device TLS certificate changed — possible MITM "
                               "or reinstall. Forget and re-pair."}
        return {"error": "pairing_failed"}
    if not known_pin and pin:
        known_pin = pin
    expires_ms = result.get("expiresAt") or 0
    device = registry.add_device(
        host=req.host, port=req.port,
        token=result["token"],
        refresh_token=result.get("refreshToken", ""),
        expires_at=expires_ms / 1000.0 if expires_ms else 0.0,
        device_id=result.get("deviceId", ""),
        name=socket.gethostname(),
        paired=True,
        cert_fp=known_pin or "",
    )
    return {"ok": True, "device": device.__dict__}

@app.post("/api/devices/refresh")
async def refresh_device(req: RefreshRequest, _=Depends(require_login)):
    key = f"{req.host}:{req.port}"
    dev = registry.get_device(key)
    if not dev:
        raise HTTPException(404, "Device not found")
    info, pin = health(dev.host, dev.port, cert_fp=dev.cert_fp or None)
    if info.get("status") == "ok":
        updates: dict[str, object] = {"last_seen": time.time()}
        if not dev.cert_fp and pin:
            updates["cert_fp"] = pin  # TOFU pin capture
        registry.update_device(key, **updates)
        # Keep the token alive so the pairing never lapses.
        if dev.paired:
            registry.ensure_device_token(key)
        return {"ok": True, "health": info}
    return {"ok": False, "error": info.get("error", "unreachable")}

@app.delete("/api/devices/{key}")
async def remove_device(key: str, _=Depends(require_login)):
    registry.remove_device(key)
    return {"ok": True}

# ---------- Device Control Routes ----------

@app.get("/api/device/{host}/{port}/health")
async def device_health_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev = registry.get_device(key)
    if not dev:
        raise HTTPException(404, "Device not found")
    info, pin = health(dev.host, dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.get("/api/device/{host}/{port}/info")
async def device_info_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev, err = _paired_device_or_error(key)
    if err:
        return err
    if dev is None:
        return {"error": "not_found", "message": "Device not found"}
    info, _ = device_info(dev.host, dev.token or "", dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.get("/api/device/{host}/{port}/location")
async def device_location_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev, err = _paired_device_or_error(key)
    if err:
        return err
    if dev is None:
        return {"error": "not_found", "message": "Device not found"}
    info, _ = location_current(dev.host, dev.token or "", dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.get("/api/device/{host}/{port}/cameras")
async def device_cameras_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev, err = _paired_device_or_error(key)
    if err:
        return err
    if dev is None:
        return {"error": "not_found", "message": "Device not found"}
    info, _ = camera_list(dev.host, dev.token or "", dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.post("/api/device/{host}/{port}/camera/capture")
async def device_camera_capture(host: str, port: int, camera_id: str,
                                 _=Depends(require_login)):
    key = f"{host}:{port}"
    dev, err = _paired_device_or_error(key)
    if err:
        return err
    if dev is None:
        return {"error": "not_found", "message": "Device not found"}
    info, _ = camera_capture(dev.host, dev.token or "", camera_id, dev.port,
                             cert_fp=dev.cert_fp or None)
    return info

# ---------- Media Catalogue & Nicknames (SQLite-backed, no phone needed) ----------

@app.get("/api/device/{host}/{port}/media")
async def device_media_list(host: str, port: int, _=Depends(require_login)):
    """List captured media metadata for a device — call recordings, videos,
    screen recordings, screenshots, photos. Read from the local DB; the
    phone does not need to be reachable."""
    key = f"{host}:{port}"
    if not registry.get_device(key):
        raise HTTPException(404, "Device not found")
    return {"ok": True, "media": registry.list_media(key)}

@app.post("/api/device/{host}/{port}/media")
async def device_media_add(host: str, port: int, req: MediaRequest,
                           _=Depends(require_login)):
    """Register a captured file's metadata for a device (used by the exfil /
    ingest pipeline; the phone app is not required for storage)."""
    key = f"{host}:{port}"
    if not registry.get_device(key):
        raise HTTPException(404, "Device not found")
    try:
        entry = registry.add_media(key, req.kind, path=req.path,
                                   size_bytes=req.size_bytes,
                                   duration_sec=req.duration_sec, note=req.note)
    except ValueError as e:
        raise HTTPException(400, str(e))
    return {"ok": True, "media": entry}

@app.delete("/api/device/{host}/{port}/media/{media_id}")
async def device_media_delete(host: str, port: int, media_id: int,
                              _=Depends(require_login)):
    registry.delete_media(media_id)
    return {"ok": True}

@app.post("/api/device/{host}/{port}/nickname")
async def device_nickname(host: str, port: int, req: NicknameRequest,
                          _=Depends(require_login)):
    """Set a human-friendly nickname for the device (stored encrypted)."""
    key = f"{host}:{port}"
    if not registry.get_device(key):
        raise HTTPException(404, "Device not found")
    registry.set_nickname(key, req.nickname)
    return {"ok": True, "nickname": req.nickname.strip()}

# ---------- Static ----------

static_dir = Path(__file__).parent.parent / "static"
if static_dir.exists():
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

# ---------- Run ----------

def run():
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)
