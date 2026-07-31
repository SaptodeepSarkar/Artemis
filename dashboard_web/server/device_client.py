"""HTTP client for Artemis phone devices."""
import json
import socket
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any


@dataclass
class ArtemisDevice:
    """Represents an Artemis Sentinel phone."""
    host: str
    port: int = 8443
    token: str | None = None
    device_id: str = ""
    name: str = ""
    paired: bool = False
    last_seen: float = 0.0

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def is_reachable(self, timeout: float = 3.0) -> bool:
        s = socket.socket()
        s.settimeout(timeout)
        try:
            s.connect((self.host, self.port))
            s.close()
            return True
        except:
            return False

    def __hash__(self):
        return hash((self.host, self.port))


def _http_request(
    host: str,
    method: str,
    path: str,
    body: dict | None = None,
    token: str | None = None,
    port: int = 8443,
    timeout: float = 5.0,
) -> tuple[int, dict[str, Any]]:
    """Raw HTTP request to an Artemis device over LAN/WiFi."""
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        body_bytes = json.dumps(body).encode() if body else b""
        req = f"{method} {path} HTTP/1.0\r\nHost: {host}\r\n"
        if token:
            req += f"Authorization: Bearer {token}\r\n"
        if body:
            req += f"Content-Length: {len(body_bytes)}\r\n"
        req += "Connection: close\r\n\r\n"
        if body:
            req += body_bytes.decode()
        s.send(req.encode())

        data = b""
        while True:
            try:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
            except:
                break
        raw = data.decode("utf-8", errors="replace")
        if not raw:
            return (0, {"error": "empty_response"})
        header_part, _, body_part = raw.partition("\r\n\r\n")
        status_line = header_part.split("\r\n")[0] if header_part else ""
        parts = status_line.split(" ")
        status = int(parts[1]) if len(parts) >= 2 else 0
        try:
            j = json.loads(body_part)
        except:
            j = {"raw": body_part[:200]}
        return (status, j)
    except socket.timeout:
        return (0, {"error": "timeout"})
    except Exception as e:
        return (0, {"error": str(e)})
    finally:
        try:
            s.close()
        except:
            pass


def health(host: str, port: int = 8443, timeout: float = 5.0) -> dict:
    _, j = _http_request(host, "GET", "/api/v1/health", port=port, timeout=timeout)
    return j


def pair(host: str, code: str, port: int = 8443) -> dict | None:
    _, j = _http_request(
        host, "POST", "/api/v1/auth/pair",
        body={"code": code}, port=port,
    )
    if "token" in j:
        return j
    return None


def device_info(host: str, token: str, port: int = 8443) -> dict:
    _, j = _http_request(
        host, "GET", "/api/v1/device/info",
        token=token, port=port,
    )
    return j


def location_current(host: str, token: str, port: int = 8443) -> dict:
    _, j = _http_request(
        host, "GET", "/api/v1/location/current",
        token=token, port=port,
    )
    return j


def camera_list(host: str, token: str, port: int = 8443) -> dict:
    _, j = _http_request(
        host, "GET", "/api/v1/camera/list",
        token=token, port=port,
    )
    # Phone returns a bare array; normalize to {"cameras": [...]}
    if isinstance(j, list):
        return {"cameras": j}
    return j


def camera_capture(host: str, token: str, camera_id: str, port: int = 8443) -> dict:
    _, j = _http_request(
        host, "POST", "/api/v1/camera/capture",
        body={"cameraId": camera_id}, token=token, port=port,
    )
    return j
