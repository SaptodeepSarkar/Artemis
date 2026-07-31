"""FastAPI app — Artemis Web Dashboard backend."""
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

app = FastAPI(title="Artemis Dashboard", version="1.3.0")
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

# ---------- Auth Routes ----------

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    token = auth.get_session_token(request)
    if auth.validate_session(token):
        return templates.TemplateResponse(request, "devices.html", {"devices": registry.get_all()})
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
    dev = registry.get_device(f"{req.host}:{req.port}")
    known_pin = dev.cert_fp if dev else None
    result, pin = pair(req.host, req.code, req.port, cert_fp=known_pin or None)
    if not result or "token" not in result:
        if result and result.get("error") == "cert_mismatch":
            return {"error": "cert_mismatch",
                    "message": "Device TLS certificate changed — possible MITM "
                               "or reinstall. Forget and re-pair."}
        return {"error": "pairing_failed"}
    if not known_pin and pin:
        known_pin = pin
    device = registry.add_device(
        host=req.host, port=req.port,
        token=result["token"],
        device_id=result.get("deviceId", ""),
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
    dev = registry.get_device(key)
    if not dev or not dev.token:
        raise HTTPException(404, "Device not found or not paired")
    info, _ = device_info(dev.host, dev.token, dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.get("/api/device/{host}/{port}/location")
async def device_location_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev = registry.get_device(key)
    if not dev or not dev.token:
        raise HTTPException(404, "Device not found or not paired")
    info, _ = location_current(dev.host, dev.token, dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.get("/api/device/{host}/{port}/cameras")
async def device_cameras_route(host: str, port: int, _=Depends(require_login)):
    key = f"{host}:{port}"
    dev = registry.get_device(key)
    if not dev or not dev.token:
        raise HTTPException(404, "Device not found or not paired")
    info, _ = camera_list(dev.host, dev.token, dev.port, cert_fp=dev.cert_fp or None)
    return info

@app.post("/api/device/{host}/{port}/camera/capture")
async def device_camera_capture(host: str, port: int, camera_id: str,
                                 _=Depends(require_login)):
    key = f"{host}:{port}"
    dev = registry.get_device(key)
    if not dev or not dev.token:
        raise HTTPException(404, "Device not found or not paired")
    info, _ = camera_capture(dev.host, dev.token, camera_id, dev.port,
                             cert_fp=dev.cert_fp or None)
    return info

# ---------- Static ----------

static_dir = Path(__file__).parent.parent / "static"
if static_dir.exists():
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

# ---------- Run ----------

def run():
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)
