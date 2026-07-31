"""Device registry — in-memory cache over the encrypted SQLite store (db.py).

The registry is the dashboard's single view of every phone: pairing state,
tokens, TLS pins, nicknames, and the per-device media catalogue (call
recordings, videos, screen recordings, screenshots, photos). Persistence
goes through server.db — the dashboard never needs the phone app running to
read or write any of this state.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

from . import db
from .device_client import (
    health as device_health,
    refresh as device_refresh,
)


@dataclass
class ArtemisDevice:
    host: str
    port: int = 8443
    token: str = ""
    device_id: str = ""
    name: str = ""
    paired: bool = False
    last_seen: float = 0.0
    cert_fp: str = ""
    refresh_token: str = ""
    expires_at: float = 0.0
    nickname: str = ""
    created_at: float = 0.0
    # Runtime-only: never persisted. Set by health probes so the fleet UI can
    # show ONLINE vs OFFLINE per device.
    online: bool = True


class DeviceRegistry:
    def __init__(self):
        self._devices: dict[str, ArtemisDevice] = {}
        self._lock = threading.Lock()
        self._discovery_callbacks: list[Callable[[str, ArtemisDevice], None]] = []
        self._load()

    def _load(self):
        for key, data in db.get_devices().items():
            data.pop("key", None)
            self._devices[key] = ArtemisDevice(**data)

    def _save(self):
        with self._lock:
            devices = list(self._devices.items())
        for key, dev in devices:
            data = {
                "key": key, "host": dev.host, "port": dev.port,
                "token": dev.token or "", "refresh_token": dev.refresh_token or "",
                "cert_fp": dev.cert_fp or "", "device_id": dev.device_id or "",
                "name": dev.name or "", "nickname": dev.nickname or "",
                "paired": dev.paired, "last_seen": dev.last_seen,
                "expires_at": dev.expires_at, "created_at": dev.created_at,
            }
            db.put_device(data)

    def add_device(self, host: str, port: int = 8443,
                   token: str = "", device_id: str = "",
                   name: str = "", paired: bool = False,
                   cert_fp: str = "", refresh_token: str = "",
                   expires_at: float = 0.0, nickname: str = "") -> ArtemisDevice:
        key = f"{host}:{port}"
        dev = ArtemisDevice(
            host=host, port=port, token=token,
            device_id=device_id, name=name, paired=paired,
            last_seen=time.time(), cert_fp=cert_fp,
            refresh_token=refresh_token, expires_at=expires_at,
            nickname=nickname,
        )
        with self._lock:
            self._devices[key] = dev
        self._save()
        for cb in self._discovery_callbacks:
            cb(key, dev)
        return dev

    def remove_device(self, key: str):
        with self._lock:
            self._devices.pop(key, None)
        db.delete_device(key)

    def get_device(self, key: str) -> ArtemisDevice | None:
        with self._lock:
            return self._devices.get(key)

    def get_all(self) -> dict[str, ArtemisDevice]:
        with self._lock:
            return dict(self._devices)

    def get_paired(self) -> dict[str, ArtemisDevice]:
        with self._lock:
            return {k: v for k, v in self._devices.items() if v.paired}

    def get_unpaired(self) -> dict[str, ArtemisDevice]:
        with self._lock:
            return {k: v for k, v in self._devices.items() if not v.paired}

    def update_device(self, key: str, **kwargs):
        with self._lock:
            dev = self._devices.get(key)
            if not dev:
                return
            for k, v in kwargs.items():
                setattr(dev, k, v)
            dev.last_seen = time.time()
        db.update_device(key, **kwargs)

    def set_nickname(self, key: str, nickname: str):
        dev = self.get_device(key)
        if not dev:
            return
        self.update_device(key, nickname=nickname.strip())

    # ------------------------------------------------------------------
    # Media catalogue (DB-only — no phone app required)
    # ------------------------------------------------------------------

    def list_media(self, key: str) -> list[dict]:
        return db.list_media(key)

    def add_media(self, key: str, kind: str, path: str = "", size_bytes: int = 0,
                  duration_sec: float = 0.0, note: str = "") -> dict:
        return db.add_media(key, kind, path=path, size_bytes=size_bytes,
                            duration_sec=duration_sec, note=note)

    def delete_media(self, media_id: int):
        db.delete_media(media_id)

    def on_discovery(self, callback: Callable[[str, ArtemisDevice], None]):
        self._discovery_callbacks.append(callback)

    def scan_subnet(self, subnet: str = "192.168.0", timeout: float = 2.0):
        """Scan a /24 subnet for Artemis devices on port 8443."""
        found = []
        for i in range(1, 255):
            host = f"{subnet}.{i}"
            key = f"{host}:8443"
            if key in self._devices:
                continue
            info, pin = device_health(host, timeout=timeout)
            if info and info.get("status") == "ok":
                dev = self.add_device(
                    host, device_id=info.get("deviceName", host),
                    cert_fp=pin,
                )
                found.append((key, dev))
        return found

    def refresh_all(self):
        """Ping all known devices, update online status + TLS pin, and keep
        tokens alive (refresh before expiry) so pairings never lapse."""
        with self._lock:
            devices = list(self._devices.items())
        for key, dev in devices:
            try:
                info, pin = device_health(dev.host, port=dev.port,
                                          timeout=3.0, cert_fp=dev.cert_fp or None)
                if info and info.get("status") == "ok":
                    updates: dict[str, object] = {"last_seen": time.time()}
                    # TOFU: capture the pin on first contact
                    if not dev.cert_fp and pin:
                        updates["cert_fp"] = pin
                    self.update_device(key, **updates)
                    # Proactively refresh before the access token expires.
                    if dev.paired:
                        self.ensure_device_token(key)
                elif info and info.get("error") == "cert_mismatch":
                    # Pin changed — possible MITM or device re-pair. Delete the
                    # trust relationship entirely: token, refresh token, pin.
                    self.force_unpair(key, reason="TLS PIN MISMATCH")
                else:
                    self.update_device(key, last_seen=dev.last_seen)  # keep old
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Token lifecycle — refresh before expiry, never silently re-pair
    # ------------------------------------------------------------------

    # Refresh when the access token has less than this much life left.
    REFRESH_MARGIN_SECONDS = 10 * 60

    def force_unpair(self, key: str, reason: str = ""):
        """Delete the trust relationship for a device: tokens AND pin are
        cleared and the device is marked unpaired. The user must re-pair."""
        with self._lock:
            dev = self._devices.get(key)
            if not dev:
                return
            dev.token = ""
            dev.refresh_token = ""
            dev.expires_at = 0.0
            dev.cert_fp = ""
            dev.paired = False
        db.update_device(
            key, token="", refresh_token="", expires_at=0.0,
            cert_fp="", paired=False,
        )
        if reason:
            print(f"[artemis] {reason} for {key} — trust relationship deleted, re-pair required", flush=True)

    def ensure_device_token(self, key: str) -> tuple[bool, str]:
        """Make sure a paired device has a usable access token, refreshing it
        (with rotation) before it expires. Returns (ok, detail).

        The user never notices: refresh happens lazily on access, well before
        the 1-hour token expiry, using the 30-day refresh token.
        """
        dev = self.get_device(key)
        if not dev:
            return (False, "unknown_device")
        if not dev.paired:
            return (False, "not_paired")
        if dev.token and dev.expires_at > 0 and \
                time.time() < dev.expires_at - self.REFRESH_MARGIN_SECONDS:
            return (True, "ok")  # token still fresh

        if not dev.refresh_token:
            # Pre-v1.5 pairing that never stored a refresh token. We must not
            # mint tokens from the access token (that would extend theft
            # windows); the dashboard must re-pair once.
            return (False, "no_refresh_token")

        try:
            status, j, pin = device_refresh(
                dev.host, dev.refresh_token, port=dev.port,
                cert_fp=dev.cert_fp or None,
            )
        except Exception as e:
            return (False, f"refresh_error: {e}")

        if status == 200 and j and j.get("token"):
            expires_ms = j.get("expiresAt") or 0
            self.update_device(
                key,
                token=j["token"],
                refresh_token=j.get("refreshToken", ""),
                expires_at=expires_ms / 1000.0 if expires_ms else 0.0,
                paired=True,
            )
            return (True, "refreshed")

        err = (j or {}).get("error", "")
        if err == "replay_detected":
            # Refresh-token reuse detected by the phone — the device revoked
            # us. Delete the local trust relationship; the user re-pairs.
            self.force_unpair(key, reason="REFRESH TOKEN REPLAY DETECTED by device")
            return (False, "replay_detected")
        if err == "invalid_token" or status in (400, 401):
            self.force_unpair(key, reason="Refresh token rejected by device")
            return (False, "invalid_token")
        if err == "cert_mismatch":
            self.force_unpair(key, reason="TLS PIN MISMATCH during refresh")
            return (False, "cert_mismatch")
        return (False, f"refresh_failed: status={status}")


# Singleton
registry = DeviceRegistry()
