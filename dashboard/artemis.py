#!/usr/bin/env python3
"""
Artemis Dashboard CLI — Remote client for Artemis Android Sentinel.

Connects to the Artemis Android app over the local network,
authenticates, and allows querying device data.

Usage:
    # Discover devices on LAN
    python3 artemis.py discover

    # Pair with a device (connect + get pairing code from user)
    python3 artemis.py pair <device-ip> [--port 8443]

    # Get device info
    python3 artemis.py info --host <ip> --token <token>

    # Get current location
    python3 artemis.py location --host <ip> --token <token>

    # Get location history (last 24h)
    python3 artemis.py location --host <ip> --token <token> --history

    # Capture camera photo
    python3 artemis.py camera --host <ip> --token <token>

    # List cameras
    python3 artemis.py camera --host <ip> --token <token> --list

    # Save token for reuse
    python3 artemis.py pair <device-ip> --save
    python3 artemis.py info --host <ip>  # uses saved token
"""

import argparse
import hashlib
import hmac
import json
import os
import socket
import struct
import sys
import time
import urllib.request
import urllib.error
import ssl
import tempfile
from pathlib import Path

DEFAULT_PORT = 8443
DISCOVERY_PORT = 9090
DISCOVERY_MAGIC = b'\x41'


# ─── Shared encrypted SQLite store ─────────────────────────────────────────
# Tokens and TOFU pins now live in the dashboard's artemis.db (Fernet-
# encrypted at rest), not in JSON files under .config. The CLI reuses the
# dashboard_web server package so there is exactly one store of truth.

def _db():
    import sys
    pkg = Path(__file__).resolve().parent.parent / "dashboard_web"
    if str(pkg) not in sys.path:
        sys.path.insert(0, str(pkg))
    from server import db
    return db


# ─── Configuration ───────────────────────────────────────────────────────────

def load_tokens():
    """Load saved tokens from the shared encrypted SQLite DB.

    Returns {host: {"token": ..., "refresh_token": ..., "expires_at": ...}}
    (already decrypted — the DB handles encryption at rest).
    """
    try:
        return _db().get_all_cli_tokens()
    except Exception:
        return {}


def save_tokens(tokens):
    """Save tokens to the shared encrypted SQLite DB (encrypted at rest)."""
    _db().set_cli_tokens(tokens)


def get_saved_token(host):
    """Get saved access token for a host."""
    tokens = load_tokens()
    return tokens.get(host, {}).get("token")


def set_saved_token(host, token, refresh_token="", expires_at=0.0):
    """Save token set for a host."""
    tokens = load_tokens()
    tokens[host] = {
        "token": token,
        "refresh_token": refresh_token,
        "expires_at": expires_at,
    }
    save_tokens(tokens)


def forget_host(host, port=DEFAULT_PORT):
    """Delete the trust relationship for a host: token, refresh token, pin."""
    tokens = load_tokens()
    tokens.pop(host, None)
    save_tokens(tokens)
    pins = _load_known_hosts()
    pins.pop(f"{host}:{port}", None)
    _save_known_hosts(pins)


# ─── Network / Discovery ────────────────────────────────────────────────────

def discover_devices(timeout=5):
    """
    Discover Artemis devices on LAN via UDP broadcast.
    Returns list of dicts with ip, port, device, version.
    """
    print(f"🔍 Scanning LAN for Artemis devices (timeout={timeout}s)...")
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(timeout)
    
    # Send discovery broadcast
    discover_msg = json.dumps({"type": "discover"}).encode()
    sock.sendto(discover_msg, ('255.255.255.255', DISCOVERY_PORT))
    
    devices = []
    start = time.time()
    while time.time() - start < timeout:
        try:
            data, addr = sock.recvfrom(1024)
            if len(data) < 2 or data[0] != DISCOVERY_MAGIC:
                continue
            try:
                info = json.loads(data[1:].decode())
                if info.get("type") == "announce":
                    devices.append({
                        "ip": addr[0],
                        "port": info.get("port", DEFAULT_PORT),
                        "device": info.get("device", "Unknown"),
                        "version": info.get("version", "?"),
                    })
                    print(f"  ✓ Found: {info.get('device', 'Unknown')} at {addr[0]}:{info.get('port', DEFAULT_PORT)}")
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
        except socket.timeout:
            break
    
    sock.close()
    
    if not devices:
        print("  ✗ No Artemis devices found. Is the app running on your device?")
        print("  Tip: Try specifying the IP directly: --host 192.168.1.x")
    
    return devices


# ─── HTTPS Client ────────────────────────────────────────────────────────────

def _create_ssl_context():
    """TLS >= 1.2 (1.3 by default), ECDHE+AEAD ciphers only; self-signed
    certs are accepted and verified by TOFU pinning by hand."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        ctx.set_ciphers("ECDHE+AESGCM:ECDHE+CHACHA20")
    except ssl.SSLError:
        pass
    return ctx


def _load_known_hosts():
    """host:port -> SHA256 pin for every device we've ever talked to (TOFU)."""
    try:
        return _db().get_known_hosts()
    except Exception:
        return {}


def _save_known_hosts(pins):
    _db().replace_known_hosts(pins)


def _pin_for(host, port):
    return _load_known_hosts().get(f"{host}:{port}")


def _remember_pin(host, port, pin):
    pins = _load_known_hosts()
    pins[f"{host}:{port}"] = pin
    _save_known_hosts(pins)


def _peer_pin(resp):
    """Extract the peer cert SHA-256 pin from an open urllib response."""
    try:
        sock = resp.fp.raw._sock  # SSLSocket under the HTTPResponse
        der = sock.getpeercert(binary_form=True)
        if der:
            return "SHA256:" + hashlib.sha256(der).hexdigest()
    except Exception:
        pass
    return None


def api_request(host, path, method="GET", data=None, token=None, port=DEFAULT_PORT):
    """Make HTTPS request to Artemis server with TOFU cert pinning.

    On 401 with a saved refresh token, transparently refreshes the token
    (rotation happens on the phone) and retries once — the user never notices.
    On a cert-pin mismatch the stored trust (token + pin) is deleted.
    """
    url = f"https://{host}:{port}{path}"

    def _do_request(auth_token):
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if auth_token:
            headers["Authorization"] = f"Bearer {auth_token}"
        body = json.dumps(data).encode() if data else None
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        ctx = _create_ssl_context()
        with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
            observed = _peer_pin(resp)
            expected = _pin_for(host, port)
            if expected and observed and not hmac.compare_digest(expected, observed):
                return {"error": "cert_mismatch",
                        "message": "Device TLS certificate changed — possible "
                                   "MITM or reinstall. Forget and re-pair."}
            if observed:
                _remember_pin(host, port, observed)
            raw = resp.read().decode()
            return json.loads(raw)

    try:
        return _do_request(token)
    except urllib.error.HTTPError as e:
        # 401 + saved refresh token -> refresh and retry once.
        if e.code == 401:
            saved = load_tokens().get(host, {})
            rt = saved.get("refresh_token")
            if rt:
                try:
                    status, j = _refresh_token(host, rt, port)
                    if status == 200 and j and j.get("token"):
                        set_saved_token(
                            host, j["token"],
                            refresh_token=j.get("refreshToken", ""),
                            expires_at=float(j.get("expiresAt") or 0) / 1000.0,
                        )
                        return _do_request(j["token"])
                    if (j or {}).get("error") == "replay_detected":
                        forget_host(host, port)
                        print(f"\n⚠ REFRESH TOKEN REPLAY DETECTED by {host} — "
                              f"device revoked us. Re-pair to continue.", flush=True)
                        return {"error": "replay_detected",
                                "message": "Device revoked this dashboard (refresh-token replay). Re-pair."}
                    forget_host(host, port)
                    return {"error": "refresh_failed",
                            "message": "Refresh token rejected — re-pair the device.",
                            "status": status}
                except Exception as ex:
                    return {"error": f"refresh_error: {ex}"}
        try:
            err = json.loads(e.read().decode())
            return {"error": err.get("error", str(e)), "status": e.code}
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {"error": f"HTTP {e.code}: {e.reason}", "status": e.code}
    except urllib.error.URLError as e:
        return {"error": f"Connection failed: {e.reason}"}
    except ssl.SSLError as e:
        return {"error": f"SSL error: {e}"}
    except Exception as e:
        return {"error": str(e)}


def _refresh_token(host, refresh_token, port=DEFAULT_PORT):
    """POST /api/v1/auth/token — returns (status, json)."""
    url = f"https://{host}:{port}/api/v1/auth/token"
    body = json.dumps({"refreshToken": refresh_token}).encode()
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    ctx = _create_ssl_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
            observed = _peer_pin(resp)
            expected = _pin_for(host, port)
            if expected and observed and not hmac.compare_digest(expected, observed):
                return 0, {"error": "cert_mismatch"}
            raw = resp.read().decode()
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except (json.JSONDecodeError, UnicodeDecodeError):
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


# ─── Pairing Flow ────────────────────────────────────────────────────────────

def cmd_pair(args):
    """Pair with an Artemis device."""
    host = args.host
    port = args.port or DEFAULT_PORT
    
    # Step 1: Check if device is reachable
    print(f"🔗 Connecting to {host}:{port}...")
    health = api_request(host, "/api/v1/health", port=port)
    if "error" in health:
        print(f"✗ Cannot reach device: {health['error']}")
        return
    
    print(f"✓ Device reachable: {json.dumps(health, indent=2)}")
    
    # Step 2: Get pairing code from user (read from phone screen)
    print()
    print("Look at the Artemis app on your phone for the pairing code.")
    print()

    if args.code:
        pairing_code = args.code
    else:
        pairing_code = input("Enter the 6-digit pairing code shown on the phone: ").strip()
    
    # Step 3: Submit pairing code
    print(f"Submitting pairing code...")
    pair_resp = api_request(host, "/api/v1/auth/pair",
                           method="POST",
                           data={"code": pairing_code},
                           port=port)
    
    if "error" in pair_resp:
        print(f"✗ Pairing failed: {pair_resp['error']}")
        return
    
    token = pair_resp.get("token")
    device_id = pair_resp.get("deviceId", "unknown")

    print(f"✓ Paired successfully with device: {device_id}")
    print(f"  Token: {token[:20]}...{token[-10:] if token else ''}")

    if args.save:
        set_saved_token(
            host, token,
            refresh_token=pair_resp.get("refreshToken", ""),
            expires_at=float(pair_resp.get("expiresAt") or 0) / 1000.0,
        )
        print(f"✓ Token + refresh token saved for {host} (auto-refreshes)")

    return token


# ─── Commands ────────────────────────────────────────────────────────────────

def _get_token(args):
    """Get token: from args, saved, or prompt."""
    if args.token:
        return args.token
    
    saved = get_saved_token(args.host)
    if saved:
        return saved
    
    print("No token provided and no saved token found.")
    print("Use: python3 artemis.py pair <host> --save")
    return None


def cmd_info(args):
    """Get device info."""
    token = _get_token(args)
    if not token:
        return
    
    info = api_request(args.host, "/api/v1/device/info", token=token, port=args.port or DEFAULT_PORT)
    
    if "error" in info:
        print(f"✗ Error: {info['error']}")
        return
    
    print("\n📱 Device Info")
    print("─" * 50)
    print(f"  Model:          {info.get('model', '?')}")
    print(f"  Manufacturer:   {info.get('manufacturer', '?')}")
    print(f"  Android:        {info.get('androidVersion', '?')} (build: {info.get('buildId', '?')})")
    print(f"  Battery:        {info.get('batteryLevel', '?')}% {'⚡' if info.get('batteryCharging') else '🔋'}")
    print(f"  WiFi:           {info.get('wifiSSID', '?')} ({info.get('signalStrength', '?')} dBm)")
    print(f"  IP:             {info.get('ipAddress', '?')}")
    print(f"  Storage:        {info.get('storageUsed', '?')} / {info.get('storageTotal', '?')}")


def cmd_location(args):
    """Get device location."""
    token = _get_token(args)
    if not token:
        return
    
    port = args.port or DEFAULT_PORT
    
    if args.history:
        from_ts = int(time.time() * 1000) - (24 * 3600 * 1000)  # last 24h
        to_ts = int(time.time() * 1000)
        path = f"/api/v1/location/history?from={from_ts}&to={to_ts}"
    else:
        path = "/api/v1/location/current"
    
    loc = api_request(args.host, path, token=token, port=port)
    
    if "error" in loc:
        print(f"✗ Error: {loc['error']}")
        return
    
    if args.history:
        points = loc.get("points", [])
        print(f"\n📍 Location History ({len(points)} points, last 24h)")
        print("─" * 50)
        for p in points[-10:]:  # show last 10
            t = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(p.get('timestamp', 0) / 1000))
            print(f"  {t} | {p.get('latitude', '?'):.6f}, {p.get('longitude', '?'):.6f} ±{p.get('accuracy', '?')}m")
        if len(points) > 10:
            print(f"  ... and {len(points) - 10} more points")
    else:
        print("\n📍 Current Location")
        print("─" * 50)
        print(f"  Lat:     {loc.get('latitude', '?')}")
        print(f"  Lng:     {loc.get('longitude', '?')}")
        print(f"  Acc:     {loc.get('accuracy', '?')}m")
        print(f"  Time:    {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(loc.get('timestamp', 0) / 1000))}")
        print(f"  Maps:    https://maps.google.com/?q={loc.get('latitude', 0)},{loc.get('longitude', 0)}")


def cmd_camera(args):
    """Camera operations."""
    token = _get_token(args)
    if not token:
        return
    
    port = args.port or DEFAULT_PORT
    
    if args.list:
        cameras = api_request(args.host, "/api/v1/camera/list", token=token, port=port)
        if "error" in cameras:
            print(f"✗ Error: {cameras['error']}")
            return
        cams = cameras.get("cameras", cameras if isinstance(cameras, list) else [])
        print("\n📷 Available Cameras")
        print("─" * 50)
        for c in cams:
            facing = "📱 Front" if c.get('facing') == 'front' else "📷 Rear" if c.get('facing') == 'rear' else "?"
            print(f"  [{c.get('id', '?')}] {facing} — {c.get('name', 'Unknown')}")
    else:
        result = api_request(args.host, "/api/v1/camera/capture",
                            method="POST",
                            data={"cameraId": args.camera_id} if args.camera_id else {},
                            token=token, port=port)
        if "error" in result:
            print(f"✗ Error: {result['error']}")
            return
        
        print("\n📷 Photo Captured!")
        print("─" * 50)
        print(f"  ID:     {result.get('id', '?')}")
        print(f"  Size:   {result.get('size', 0) / 1024:.1f} KB")
        print(f"  URL:    {result.get('url', '?')}")
        
        # Optionally download
        if args.download:
            print(f"  Downloading...")
            photo_url = f"https://{args.host}:{port}{result.get('url', '')}"
            try:
                ctx = _create_ssl_context()
                dl_req = urllib.request.Request(photo_url)
                if token:
                    dl_req.add_header("Authorization", f"Bearer {token}")
                with urllib.request.urlopen(dl_req, context=ctx) as resp:
                    data = resp.read()
                fname = f"artemis_photo_{result.get('id', 'unknown')}.jpg"
                Path(fname).write_bytes(data)
                print(f"  Saved to: {fname}")
            except Exception as e:
                print(f"  ✗ Download failed: {e}")


def cmd_discover(args):
    """Discover devices on LAN."""
    devices = discover_devices(timeout=args.timeout or 5)
    return devices


def cmd_mic(args):
    """Record audio from device microphone."""
    token = _get_token(args)
    if not token:
        return
    
    port = args.port or DEFAULT_PORT
    
    if args.duration:
        result = api_request(args.host, f"/api/v1/mic/record",
                            method="POST",
                            data={"durationMs": args.duration * 1000},
                            token=token, port=port)
        if "error" in result:
            print(f"✗ Error: {result['error']}")
            return
        print(f"\n🎤 Recording complete!")
        print(f"  ID:   {result.get('id', '?')}")
        print(f"  Size: {result.get('size', 0) / 1024:.1f} KB")
        print(f"  URL:  {result.get('url', '?')}")
    else:
        recordings = api_request(args.host, "/api/v1/mic/recordings", token=token, port=port)
        if "error" in recordings:
            print(f"✗ Error: {recordings['error']}")
            return
        print("\n🎤 Recordings")
        print("─" * 50)
        for r in recordings.get("recordings", recordings if isinstance(recordings, list) else []):
            t = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(r.get('createdAt', 0) / 1000))
            print(f"  [{r.get('id', '?')}] {t} — {r.get('size', 0) / 1024:.1f} KB")


# ─── CLI ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Artemis Dashboard — Remote client for Artemis Android Sentinel",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 artemis.py discover
  python3 artemis.py pair 192.168.1.42 --save
  python3 artemis.py info --host 192.168.1.42
  python3 artemis.py location --host 192.168.1.42
  python3 artemis.py location --host 192.168.1.42 --history
  python3 artemis.py camera --host 192.168.1.42 --download
  python3 artemis.py camera --host 192.168.1.42 --list
  python3 artemis.py mic --host 192.168.1.42 --duration 10
        """
    )
    
    subparsers = parser.add_subparsers(dest="command", help="Command")
    
    # discover
    p_discover = subparsers.add_parser("discover", help="Discover Artemis devices on LAN")
    p_discover.add_argument("--timeout", type=int, default=5, help="Discovery timeout in seconds")
    
    # pair
    p_pair = subparsers.add_parser("pair", help="Pair with an Artemis device")
    p_pair.add_argument("host", help="Device IP address")
    p_pair.add_argument("--port", type=int, default=DEFAULT_PORT, help="Server port")
    p_pair.add_argument("--save", action="store_true", help="Save auth token")
    p_pair.add_argument("--code", help="Pairing code (if you already have it)")
    
    # info
    p_info = subparsers.add_parser("info", help="Get device information")
    p_info.add_argument("--host", required=True, help="Device IP")
    p_info.add_argument("--port", type=int, default=DEFAULT_PORT)
    p_info.add_argument("--token", help="Auth token")
    
    # location
    p_loc = subparsers.add_parser("location", help="Get device location")
    p_loc.add_argument("--host", required=True)
    p_loc.add_argument("--port", type=int, default=DEFAULT_PORT)
    p_loc.add_argument("--token")
    p_loc.add_argument("--history", action="store_true", help="Show 24h location history")
    
    # camera
    p_cam = subparsers.add_parser("camera", help="Camera operations")
    p_cam.add_argument("--host", required=True)
    p_cam.add_argument("--port", type=int, default=DEFAULT_PORT)
    p_cam.add_argument("--token")
    p_cam.add_argument("--list", action="store_true", help="List cameras")
    p_cam.add_argument("--camera-id", help="Camera ID to use")
    p_cam.add_argument("--download", action="store_true", help="Download photo")
    
    # microphone
    p_mic = subparsers.add_parser("mic", help="Microphone operations")
    p_mic.add_argument("--host", required=True)
    p_mic.add_argument("--port", type=int, default=DEFAULT_PORT)
    p_mic.add_argument("--token")
    p_mic.add_argument("--duration", type=int, help="Recording duration in seconds (omit to list recordings)")
    
    args = parser.parse_args()
    
    if not args.command:
        parser.print_help()
        return
    
    commands = {
        "discover": cmd_discover,
        "pair": cmd_pair,
        "info": cmd_info,
        "location": cmd_location,
        "camera": cmd_camera,
        "mic": cmd_mic,
    }
    
    commands[args.command](args)


if __name__ == "__main__":
    main()
