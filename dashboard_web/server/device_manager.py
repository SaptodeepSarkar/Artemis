"""Device registry — stores paired devices and manages discovery."""
import json
import socket
import threading
import time
from pathlib import Path
from typing import Callable
from . import config
from .device_client import ArtemisDevice, health as device_health


class DeviceRegistry:
    """Manages known devices and network discovery."""

    def __init__(self):
        self._devices: dict[str, ArtemisDevice] = {}  # key = "host:port"
        self._lock = threading.Lock()
        self._discovery_callbacks: list[Callable[[str, ArtemisDevice], None]] = []
        self._load()

    def _load(self):
        raw = config.load_devices()
        with self._lock:
            for key, data in raw.items():
                self._devices[key] = ArtemisDevice(**data)

    def _save(self):
        with self._lock:
            raw = {k: v.__dict__ for k, v in self._devices.items()}
        config.save_devices(raw)

    def add_device(self, host: str, port: int = 8443,
                   token: str = "", device_id: str = "",
                   name: str = "", paired: bool = False,
                   cert_fp: str = "") -> ArtemisDevice:
        key = f"{host}:{port}"
        dev = ArtemisDevice(
            host=host, port=port, token=token,
            device_id=device_id, name=name, paired=paired,
            last_seen=time.time(), cert_fp=cert_fp,
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
        self._save()

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
        self._save()

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
        """Ping all known devices and update online status + TLS pin."""
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
                elif info and info.get("error") == "cert_mismatch":
                    # Pin changed — possible MITM or device re-pair. Force
                    # re-pair and log the mismatch loudly.
                    self.update_device(key, paired=False, last_seen=dev.last_seen)
                    print(f"[artemis] TLS PIN MISMATCH for {key} — device forced unpaired", flush=True)
                else:
                    self.update_device(key, last_seen=dev.last_seen)  # keep old
            except:
                pass


# Singleton
registry = DeviceRegistry()
