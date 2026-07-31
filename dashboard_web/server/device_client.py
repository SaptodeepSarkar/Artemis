"""HTTP client for Artemis phone devices — TLS with TOFU cert pinning."""
import hashlib
import json
import socket
import ssl
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
    cert_fp: str = ""  # SHA-256 fingerprint of the device TLS cert (pin)

    @property
    def base_url(self) -> str:
        return f"https://{self.host}:{self.port}"

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


def _pin_of(peer_der: bytes) -> str:
    """'SHA256:<hex>' fingerprint of a peer certificate."""
    return "SHA256:" + hashlib.sha256(peer_der).hexdigest()


def _http_request(
    host: str,
    method: str,
    path: str,
    body: dict | None = None,
    token: str | None = None,
    port: int = 8443,
    timeout: float = 5.0,
    cert_fp: str | None = None,
) -> tuple[int, dict[str, Any], str]:
    """HTTPS request to an Artemis device.

    Pinning semantics (TOFU):
      - cert_fp is None/""  -> accept any cert, return its pin (first contact)
      - cert_fp provided    -> reject unless the peer cert matches the pin
    Returns (status, json, observed_pin).
    """
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # self-signed device cert; we pin by SHA-256

    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        try:
            tls = ctx.wrap_socket(s, server_hostname=host)
        except ssl.SSLError as e:
            return (0, {"error": f"TLS handshake failed: {e}"}, "")
        except Exception as e:
            return (0, {"error": f"TLS error: {e}"}, "")

        peer_der = tls.getpeercert(binary_form=True)
        observed_pin = _pin_of(peer_der) if peer_der else ""
        if cert_fp and observed_pin and cert_fp != observed_pin:
            return (0, {
                "error": "cert_mismatch",
                "message": "Device TLS certificate does not match stored pin — "
                           "possible MITM or device was re-paired. Forget and re-pair.",
                "observed_pin": observed_pin,
            }, observed_pin)

        body_bytes = json.dumps(body).encode() if body else b""
        req = f"{method} {path} HTTP/1.0\r\nHost: {host}\r\n"
        if token:
            req += f"Authorization: Bearer {token}\r\n"
        if body:
            req += f"Content-Length: {len(body_bytes)}\r\n"
        req += "Connection: close\r\n\r\n"
        if body:
            req += body_bytes.decode()
        tls.send(req.encode())

        data = b""
        while True:
            try:
                chunk = tls.recv(4096)
                if not chunk:
                    break
                data += chunk
            except:
                break
        tls.close()

        raw = data.decode("utf-8", errors="replace")
        if not raw:
            return (0, {"error": "empty_response"}, observed_pin)
        header_part, _, body_part = raw.partition("\r\n\r\n")
        status_line = header_part.split("\r\n")[0] if header_part else ""
        parts = status_line.split(" ")
        status = int(parts[1]) if len(parts) >= 2 else 0
        try:
            j = json.loads(body_part)
        except:
            j = {"raw": body_part[:200]}
        return (status, j, observed_pin)
    except socket.timeout:
        return (0, {"error": "timeout"}, "")
    except Exception as e:
        return (0, {"error": str(e)}, "")
    finally:
        try:
            s.close()
        except:
            pass


def health(host: str, port: int = 8443, timeout: float = 5.0,
           cert_fp: str | None = None) -> tuple[dict, str]:
    _, j, pin = _http_request(host, "GET", "/api/v1/health", port=port,
                              timeout=timeout, cert_fp=cert_fp)
    return j, pin


def pair(host: str, code: str, port: int = 8443,
         cert_fp: str | None = None) -> tuple[dict | None, str]:
    _, j, pin = _http_request(
        host, "POST", "/api/v1/auth/pair",
        body={"code": code}, port=port, cert_fp=cert_fp,
    )
    return j, pin


def device_info(host: str, token: str, port: int = 8443,
                cert_fp: str | None = None) -> tuple[dict, str]:
    _, j, pin = _http_request(
        host, "GET", "/api/v1/device/info",
        token=token, port=port, cert_fp=cert_fp,
    )
    return j, pin


def location_current(host: str, token: str, port: int = 8443,
                     cert_fp: str | None = None) -> tuple[dict, str]:
    _, j, pin = _http_request(
        host, "GET", "/api/v1/location/current",
        token=token, port=port, cert_fp=cert_fp,
    )
    return j, pin


def camera_list(host: str, token: str, port: int = 8443,
                cert_fp: str | None = None) -> tuple[dict, str]:
    _, j, pin = _http_request(
        host, "GET", "/api/v1/camera/list",
        token=token, port=port, cert_fp=cert_fp,
    )
    # Phone returns a bare array; normalize to {"cameras": [...]}
    if isinstance(j, list):
        return {"cameras": j}, pin
    return j, pin


def camera_capture(host: str, token: str, camera_id: str, port: int = 8443,
                   cert_fp: str | None = None) -> tuple[dict, str]:
    _, j, pin = _http_request(
        host, "POST", "/api/v1/camera/capture",
        body={"cameraId": camera_id}, token=token, port=port, cert_fp=cert_fp,
    )
    return j, pin
