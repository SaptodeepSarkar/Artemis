# Artemis Dashboard

Desktop CLI client for communicating with Artemis Android Sentinel devices.

## Quick Start

```bash
# Discover devices on your LAN
python3 artemis.py discover

# Pair with a device (get code from device screen)
python3 artemis.py pair 192.168.1.42 --save

# Get device info (uses saved token)
python3 artemis.py info --host 192.168.1.42

# Get current location
python3 artemis.py location --host 192.168.1.42

# Capture a photo
python3 artemis.py camera --host 192.168.1.42 --download
```

## Commands

| Command | Description |
|---------|-------------|
| `discover` | Scan LAN for Artemis devices |
| `pair` | Authenticate with a device |
| `info` | Get device info (model, battery, WiFi) |
| `location` | Get current location or 24h history |
| `camera` | List cameras or capture photo |
| `mic` | List recordings or record audio |

## Requirements

Python 3.7+ (no external dependencies — uses only stdlib).
