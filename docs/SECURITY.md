# Artemis Sentinel — Security Architecture

Version 2.2.0 · August 2026 · Applies to the Android app (Artiest/), the web
dashboard (dashboard_web/) and the CLI (dashboard/artemis.py).

This document covers the transport, authentication, token lifecycle, pairing,
storage and session-management design, the threat model, and the migration
path from v1.4.0. The pairing UX is unchanged: open the app → read the 6-digit
code → enter it once → the dashboard stays paired forever (token refresh is
automatic and invisible).

---

## 1. Architecture changes (v1.4.0 → v1.5.0)

| Area | v1.4.0 | v1.5.0 |
|---|---|---|
| TLS | TLS 1.2 only, default cipher list | TLS 1.3 by default, TLS 1.2 fallback restricted to ECDHE + AEAD (forward secrecy), server cipher-suite order, handshake timeout |
| Access token | 24 h | 1 h |
| Refresh token | stateless signed blob, never expired, never revoked, reusable forever | stateful (SHA-256 hash stored in EncryptedSharedPreferences), 30 d lifetime, rotated on every use, replay detection with device revocation |
| Access-token revocation | revocation list cleared in bulk at 1000 entries (replay window) | revocation list pruned only by age (7 d) |
| Client revocation | isActive flag only; outstanding tokens stayed valid until expiry | isActive enforced at validation time (revoked clients are rejected immediately), all their refresh tokens revoked |
| Pairing lockout | flat 5-min lockout after 5 failures | exponential backoff: 60 s → 120 s → 240 s → capped at 5 min (resets on code rotation) |
| Pairing code | rotated every 5 min, on restart | additionally rotated immediately after a successful pairing |
| Client IDs | `client_<millis>` (predictable, collision-prone) | `client_<12 random bytes>` (base64url) |
| Device name | hardcoded "Web Dashboard" | optional `name` in the pair request (dashboard sends its hostname) |
| lastSeen | never updated | updated on every authenticated request (persisted at most once/min) |
| Error handling | exception messages echoed in HTTP 500 responses | generic response, details only in logcat |
| Android cleartext | `usesCleartextTraffic="true"` (all interfaces) | network-security-config: cleartext allowed **only** to loopback |
| Desktop token storage | access token encrypted (Fernet) | access + refresh token + cert pin encrypted (Fernet, 0600 key) |
| Pin comparison | plain string `!=` | constant-time (`hmac.compare_digest`) |
| Pin mismatch handling | marked unpaired, token kept | trust relationship fully deleted (token + refresh token + pin), re-pair required, loud warning |
| Dashboard auth | in-memory sessions, `==` password compare | constant-time password compare (`hmac.compare_digest`) |
| Session management | API only, no UI | phone Settings → "Paired Dashboards": list (name, paired date, last seen, active), revoke one, revive, revoke all |
| **Bug fixed** | web dashboard sent `Authorization: Bearer ***` (literal) | sends the real token — authenticated endpoints now actually work from the web UI |

---

## 2. Security rationale

### 2.1 TLS 1.3 with a restricted TLS 1.2 fallback

- The server socket is created with `SSLContext.getInstance("TLS")` (the
  platform default provider, Conscrypt on Android) and the accepted sockets
  advertise `TLSv1.3, TLSv1.2` in that order, so **TLS 1.3 is negotiated
  whenever the peer supports it**.
- **Compatibility analysis (why the fallback is safe here):**
  - Android: the app's `minSdkVersion` is 29 (Android 10). Conscrypt on
    Android 10+ supports TLS 1.3, so every device that can install this app
    can negotiate TLS 1.3. There is no Android < 10 compatibility cost — the
    user's override ("don't force TLS 1.3 if Android < 10 suffers") does not
    bite because there is no supported device below Android 10.
  - Dashboards: Python 3.12 + OpenSSL 3.x (web dashboard and CLI) negotiate
    TLS 1.3 by default; we additionally set `minimum_version = TLSv1_2`.
  - The TLS 1.2 fallback exists only for hypothetical peers that speak only
    1.2 (e.g. ancient Python builds, non-Conscrypt clients).
- **TLS 1.2 fallback cipher suites are restricted** to ECDHE key exchange +
  AEAD (GCM/CHACHA20) only — no RSA key exchange, no CBC, no RC4/3DES. Every
  allowed suite provides forward secrecy. TLS 1.3's mandatory suites are all
  AEAD and cannot be weakened by configuration.
- **Renegotiation:** TLS 1.3 removed renegotiation entirely. On the TLS 1.2
  fallback path, Conscrypt (Android 10+) rejects client-initiated
  renegotiation by default and the server never calls `startHandshake` twice.
- **Certificate chain:** the phone presents a self-signed certificate; there
  is no chain to walk. Chain-equivalent verification is done by the clients'
  TOFU pinning (Section 3), which pins the exact certificate — stronger than
  chain validation for a self-signed deployment.
- **Forward secrecy:** guaranteed on TLS 1.3; guaranteed on the 1.2 fallback
  by the ECDHE-only suite list.
- **Handshake DoS:** the accepted socket's `soTimeout` is set to 15 s before
  `startHandshake`, so a peer that opens a connection and never completes a
  handshake cannot pin a server thread forever.
- **Pinning stays functional:** the certificate is unchanged (RSA-2048
  self-signed, regenerated only if the keystore file is missing), so all
  stored pins remain valid across the upgrade.

### 2.2 Token lifecycle (1 h access + 30 d refresh, rotation, replay detection)

```
PAIRING                          REFRESH (every <= 50 min, automatic)
─────────                        ─────────────────────────────────────
User enters code                 Dashboard: access token age > 50 min
        │                                │
        ▼                                ▼
Phone verifies (constant time)   POST /api/v1/auth/token {refreshToken}
        │                                │
        ▼                                ▼
Phone issues:                    Phone looks up SHA-256(refreshToken)
  access token      (1 h)        ├─ record found, current ──► Valid
  refresh token     (30 d)       │      rotate: new pair issued,
  deviceId                       │      old record marked replacedBy,
  expiresAt                       │      replacedAt=now (60 s grace)
                                 ├─ record found, rotated,
        │                        │   reuse within 60 s ────► retry path:
        ▼                        │      treated as a lost response; a fresh
Dashboard stores both            │      pair is issued again (chain extends)
tokens (Fernet-encrypted)        │
                                 ├─ record found, rotated,
                                 │   reuse after 60 s ─────► REPLAY:
                                 │      client revoked, all its refresh
                                 │      tokens revoked, re-pair required
                                 ├─ record found, expired ──► 401 invalid,
                                 │      client unregistered (per policy)
                                 └─ unknown / bad sig ──────► 401 invalid
```

- **Access tokens** remain stateless HMAC-SHA256-signed (`AT1.<payload>.sig`,
  key in AndroidKeyStore), now with a **1-hour** lifetime, and validation
  additionally requires the client to exist and be `isActive`. Revocation of
  a client therefore takes effect immediately, not at token expiry.
- **Refresh tokens** are stateful. The phone stores only the **SHA-256 hash**
  of the 256-bit random token secret, plus clientId, issue/expiry, and the
  rotation chain (`replacedBy`/`replacedAt`). The raw token is never stored
  on the phone.
- **Rotation:** every successful refresh issues a new refresh token and marks
  the presented one superseded. A superseded token can never be used again.
- **Replay detection with a retry grace:** a rotated token reused within
  60 seconds is treated as a network retry (the dashboard never received the
  rotated pair) and a fresh pair is issued; reuse after 60 s is treated as
  theft — the client is revoked, all its refresh tokens are revoked, and the
  dashboard must re-pair. The 60 s grace keeps flaky networks from
  force-unpairing the user while still catching token theft (an attacker
  replaying a stolen token later triggers revocation).
- **Refresh happens before expiry:** the dashboard refreshes lazily before
  every authenticated call when the access token has less than 10 minutes of
  life left, and proactively on device refresh. The user never sees a 401.
- **Refresh tokens are never reusable** (the only exception is the 60 s
  retry grace, which issues a *new* pair and extends the chain).

### 2.3 Registration policy

A paired dashboard stays registered until one of:

1. user removes it (web dashboard "Forget" / CLI `unpair` / phone revoke),
2. app reinstall / phone factory reset (storage is wiped),
3. dashboard revokes the device or the phone revokes the dashboard,
4. the refresh token expires (30 days without any contact — the phone
   unregisters the client),
5. a security violation is detected (replay, cert-pin mismatch).

**Token expiration never unregisters a device.** A lapsed access token just
forces the next refresh; nothing is deleted.

### 2.4 TOFU pinning

- The pin (SHA-256 of the device certificate DER, `SHA256:<hex>`) is stored
  at rest **encrypted** (Fernet) in `~/.config/artemis/artemis.db` — the
  SQLite database that replaced the legacy `devices.json` / `known_hosts.json`
  / `tokens.json` files (migrated once, then the JSON files are deleted).
  Encrypting the pin closes the "attacker with read-only file access swaps in
  their own pin" scenario.
- Comparisons are constant-time (`hmac.compare_digest`).
- On a mismatch the client **rejects the connection, deletes the entire
  trust relationship** (token, refresh token, pin), marks the device unpaired
  and logs a loud warning. A pin is never silently overwritten (TOFU capture
  only happens when no pin is stored yet).

### 2.5 Pairing hardening

- 6-digit code from `SecureRandom` (unbiased), displayed on screen only.
- The code is never logged, never returned by any API, never stored on disk.
- Constant-time comparison (`MessageDigest.isEqual`).
- Rotated every 5 minutes, on server restart, on manual regeneration, and
  **immediately after a successful pairing** (a code that already paired is
  dead within milliseconds).
- Brute-force defense per IP: failures are counted; after the 5th failure the
  IP is locked out with exponential backoff (60 s → 120 s → 240 s → 5 min
  cap, `Retry-After` returned). Rotation of the code clears all counters.
- A per-IP 120 req/min rate limit (loopback exempt) covers the rest of the
  API.

### 2.6 Mutual authentication (mTLS) — evaluated, not implemented

**Not feasible without changing the UX, by design.** Rationale:

1. **The 6-digit code is the bootstrap.** The pairing UX (read code → type
   code) provides no channel to deliver a client certificate to the
   dashboard. Doing mTLS properly would require exporting a PKCS12 from the
   phone (or a QR code), importing it into the dashboard, and managing
   revocation/rotation of per-dashboard client certs — a fundamental UX
   change, which this task forbids.
2. **The phone's TLS key cannot be hardware-backed** on the target device
   (Samsung + AndroidKeyStore RSA breaks Conscrypt handshakes, documented in
   TlsManager.kt), so the mTLS "hardware-backed client key" promise is
   unachievable on the platform anyway.
3. **Application-layer mutual auth already covers the same ground:**
   - phone → dashboard: the pairing code (one-time proof of physical access)
     plus refresh-token possession (ongoing proof, rotated, replay-revoked);
   - dashboard → phone: TOFU cert pin (exact-certificate verification,
     mismatch → trust deleted).
   mTLS would authenticate the *channel* the same way the app layer
   authenticates the *session*, with strictly worse UX and cert logistics.

**Future work (requires the user to opt into a UX change):** QR-based pairing
that imports a per-dashboard client cert, enabling true mTLS.

### 2.7 Key protection

- **HMAC signing key:** AndroidKeyStore (`AES/HMAC-SHA256`), hardware-backed
  where the SoC supports it, non-exportable.
- **TLS key:** RSA-2048 generated with BouncyCastle (software) into an
  app-private PKCS12 in `filesDir` (0600). Rationale: the AndroidKeyStore RSA
  keys fail all Conscrypt TLS handshakes on Samsung devices (error 04000044),
  so the software key is the only reliable option on the target hardware.
  The keystore password is a fixed app secret — the real boundary is the app
  sandbox, not the password. This is a documented, accepted risk.
- **Certificate rotation:** the cert is regenerated automatically if the
  keystore file is missing or corrupt; rotating it intentionally (delete the
  file) invalidates all stored pins, which forces re-pairing by design (TOFU
  mismatch → trust deletion). There is deliberately no silent cert rotation.

### 2.8 Session management (requirement 9)

- **Phone (new Settings → "Paired Dashboards"):** lists every paired
  dashboard with name, pairing date, last-seen timestamp and active state;
  supports revoking one dashboard, reviving a revoked one (mis-click
  recovery), and revoking all dashboards ("revoke lost device").
- **Web dashboard:** forget a device (removes local registry entry);
  token/refresh-token deletion is automatic on replay/mismatch.
- **Stolen refresh token:** replay detection (Section 2.2) revokes the
  device automatically; the phone screen shows the dashboard as revoked.

---

## 3. TLS handshake flow

```
Dashboard                                     Phone (port 8443)
   │  TCP connect                                   │
   │───────────────────────────────────────────────►│
   │  TLS 1.3 ClientHello (or TLS 1.2 + ECDHE list) │
   │───────────────────────────────────────────────►│
   │  ServerHello (TLS 1.3), Certificate (self-     │
   │  signed RSA-2048), key share, ... finished     │
   │◄───────────────────────────────────────────────│
   │  Finished                                      │
   │───────────────────────────────────────────────►│
   │  HTTP request + Authorization: Bearer <token>  │
   │───────────────────────────────────────────────►│
   │  HTTP response                                 │
   │◄───────────────────────────────────────────────│
   Dashboard: compare peer cert SHA-256 against
   stored pin (constant time). Mismatch → abort,
   delete trust. First contact → store pin (TOFU).
```

Notes: hostname verification is off (the cert is self-signed and pinned by
fingerprint — hostname checking would be meaningless); the pin check is the
trust anchor. Loopback connections (phone UI) skip TLS; the network security
config forbids cleartext everywhere except 127.0.0.1/::1.

---

## 4. Pairing flow

```
User opens app ──► phone generates SecureRandom 6-digit code (shown on
                   screen, never logged/networked; rotates every 5 min)

User opens dashboard ──► POST /api/v1/auth/pair {"code","name"}
        │                    │
        │                    ▼
        │         phone: rate-limit check (120 req/min/IP, loopback exempt)
        │         phone: constant-time code compare
        │         ├─ fail ──► 5th+ failure ──► exponential lockout (429)
        │         └─ match ──► (a) client created: client_<random>,
        │                        name from request, pairedAt=now
        │                      (b) access token (1 h) + refresh token (30 d)
        │                      (c) pairing code ROTATED immediately
        │                      (d) failure counters cleared
        ▼
Dashboard: TOFU-pin the cert (first contact), store both tokens
           encrypted, mark paired. Done — user never pairs again
           (refresh is automatic; re-pair only on intentional
           unpair/reinstall/revocation/30 d offline).
```

---

## 5. Threat model

| Threat | Status | Mitigation |
|---|---|---|
| Wi-Fi sniffing | ✓ protected | TLS 1.3 / ECDHE-only 1.2; no plaintext outside loopback |
| MITM (LAN/Tailscale attacker) | ✓ protected | TOFU pin (exact cert), mismatch → trust deleted + re-pair; refresh rotation limits stolen-session value |
| Replay (access token) | ✓ protected | 1 h lifetime + revocation list pruned by age (7 d) |
| Replay (refresh token) | ✓ protected | rotation; reuse after 60 s grace revokes the device |
| Token theft (access) | ✓ mitigated | 1 h window; client revoke kills immediately (isActive check) |
| Token theft (refresh) | ✓ mitigated | replay detection revokes device; rotation limits window |
| Brute-force pairing | ✓ protected | 10^6 space + 5-fail exponential lockout + 5-min code rotation + per-IP rate limit |
| Timing attacks (code/pin/password) | ✓ protected | `MessageDigest.isEqual` / `hmac.compare_digest` everywhere |
| Certificate replacement | ✓ protected | pin mismatch → reject + delete trust + re-pair |
| Local malware on phone | ⚠ residual | app sandbox + AndroidKeyStore HMAC key; TLS key is software (Samsung bug) — an attacker with root can extract it (same trust boundary as any app secret) |
| Rooted dashboard | ⚠ residual | tokens live in Fernet-encrypted files; a rooted host can read the key and decrypt (same as any local-credential system; OS credential store is the future hardening) |
| Refresh-retry false revocation | ✓ handled | 60 s grace treats retries as retries; chain extends instead of revoking |
| DoS (handshake / slowloris) | ✓ mitigated | 15 s handshake timeout; 120 req/min/IP rate limit |

**Remaining risks (explicit):**
1. A device with a compromised/rooted OS can read app-private files
   (TLS keystore password is fixed, not hardware-bound).
2. The dashboard host's local secrets are only as strong as its filesystem
   permissions + Fernet key (0600) — no OS keychain integration yet.
3. During the 60 s retry grace, a stolen refresh token can obtain one more
   pair; the next use after grace revokes the client.
4. A client that goes offline for 30 days must re-pair (refresh expiry).
5. Pairing code is 6 digits (10^6) — safe only because of the lockout; a
   distributed brute force across many IPs could still try ~10^3 codes per
   rotation window per IP; LAN-only exposure limits this further.

---

## 6. Migration plan (v1.4.0 → v1.5.0)

1. **Build & install the new app** (`./gradlew :app:assembleDebug`).
   - The TLS keystore file persists across app updates → **cert fingerprint
     unchanged** → all stored pins stay valid, no forced re-pair.
   - The HMAC key persists in AndroidKeyStore → previously issued access
     tokens keep validating until expiry.
2. **Upgrade the web dashboard** (`dashboard_web/`), **restart it**.
   - `devices.json` decrypts the old `token`; the new `refresh_token` field
     is empty for pre-1.5 pairings.
   - **One-time re-pair required** for pairings whose stored access token is
     already expired (status quo: a >24 h old token was dead anyway). Pairing
     UX is identical. After that single re-pair, the dashboard stores the
     refresh token and stays paired forever.
   - Cert pins are re-encrypted on next save (Fernet) — no user action.
3. **CLI:** `tokens.json` is migrated in place on first load; pair once with
   `--save` to capture a refresh token. After that, 401s auto-refresh.
4. Verify: `python3 -m compileall dashboard_web dashboard`, then open the web
   dashboard and confirm the device shows paired + reachable, and that
   info/location/cameras work (previously broken by the `Bearer ***` bug).
5. Phone: Settings → Paired Dashboards shows the dashboard with a live
   last-seen; revoke/revive work from the phone.

## 7. Backward compatibility analysis

| Surface | Compatible? | Notes |
|---|---|---|
| Pairing UX | ✅ identical | 6-digit code, one-time entry |
| Stored cert pins | ✅ | cert file persists across update; fingerprint unchanged |
| Existing access tokens | ✅ | same format + same HMAC key; valid until their (now 1 h) expiry… |
| Old-format refresh tokens | ✅ | legacy tokens are accepted once and migrated to the new stateful chain on first refresh |
| Old-format revoked-token store | ✅ | legacy JSON array migrates to the timestamped map |
| Old-format `tokens.json` (CLI) | ✅ | string values migrate to dict entries |
| Old-format `devices.json` | ✅ | plaintext token pass-through still decrypts; new fields default empty |
| Pairing before refresh support | ⚠ one re-pair | unavoidable: there is no safe way to mint a refresh token from an access token (would extend theft windows) |
| App update with old dashboard | ⚠ | old dashboard keeps working until its access token expires, then must be upgraded (it cannot refresh) — same as today's 24 h expiry behavior |
