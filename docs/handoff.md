# Artemis Sentinel — Handoff: Dashboard UX polish (v2.3.2)

> Written 2026-08-01, after v2.3.2 shipped (dashboard UX per user
> feedback; live-verified in-browser). Read `docs/AGENTS.md` first
> (project reference + frozen-sector rules), then `docs/SECURITY.md`
> (threat model), then this document, then the source.

---

## 1. DONE — what shipped in v2.3.2 (all verified in the browser)

User feedback that drove this phase: "call-log delete is solved but SMS
delete is not"; "when I open the dashboard it should fetch the location
and show it"; "the SCREEN button flips to rear cam — make that button
gone and make FLIP work"; "the camera feed has a weird FRONT text —
remove it". Deliverable: dashboard-only changes (NO phone rebuild —
the phone stays on v2.3.1, versionCode 7).

### 1.1 SMS delete removed (Android-blocked by design)

- The per-message delete button and `deleteSms()` are REMOVED from the
  web dashboard. SMS-row deletion is silently no-op'd by Android for
  non-default SMS apps, and Artemis is deliberately NEVER the default
  SMS app (user mandate — the stock Samsung app keeps receiving SMS),
  so a delete button that always fails was dead UI.
- The SMS panel now shows a small READ-ONLY note stating the caveat.
- Call-log delete is UNCHANGED and still works (`WRITE_CALL_LOG`,
  pm-grantable on Samsung) — delete buttons remain on call-log rows.
- Phone-side `DELETE ACCESS` info card (DashboardScreen.kt) unchanged —
  it already documents the same caveat. The `/api/v1/sms/delete`
  endpoint still exists (returns the honest error) for scripted use.

### 1.2 Location auto-fetch on dashboard open

- `getLocation()` now runs automatically when the device dashboard
  loads (top-level call in `dashboard.js`), and REFRESH
  (`refreshDevice()`) also refreshes location. The LOCATION card shows
  LAT/LON, ±accuracy, Google-Maps embed and fix time without any click.
  ACQUIRE FIX / OPEN IN MAPS buttons unchanged.
- VERIFIED: opening the page auto-populated LAT 25.4507 / LON 81.7493
  with the map iframe + FIX_ACQUIRED timestamp.

### 1.3 LIVE VIEW is camera-only; FLIP works; status badge gone

- The SCREEN ⇄ CAM toggle button is REMOVED. LIVE VIEW always streams
  the camera (`liveSource` fixed to `"cam"` in `dashboard.js`); the
  phone's screen feed (ch 1) and screen mode remain available via the
  raw API (`/api/v1/ws/live` with `{"cmd":"source","v":"screen"}`) for
  scripted use — just no dashboard button.
- `toggleFlip()` has no more `liveSource !== "cam"` guard — FLIP always
  toggles back ⇄ front; the label syncs. VERIFIED: back → FRONT → BACK
  with the canvas rendering at every step, connection held.
- The status badge (`LIVE · CAM · FRONT` overlay, element `liveStatus`)
  is REMOVED — no more weird FRONT text on the feed.
- The front-camera PiP (button + wrap canvas) is REMOVED: with cam-only
  streaming the phone does not emit the ch-3 PiP stream, so the PiP box
  would have been a dead black square; the front camera is reachable
  via FLIP instead.
- Audio is unchanged (AUDIO toggle + WebAudio, auto-ON at stream start).
- VERIFIED in-browser: START → back cam 1088×1088 canvas lit (WS open)
  → FLIP → front cam lit, label "FLIP: FRONT" → FLIP back → STOP clean.

## 2. NEXT phase — candidates (pick what fits)

1. **Screen feed in the dashboard again**: if the user later wants the
   screen view back in the UI, re-add a feed-mode toggle that sends
   `{"cmd":"source","v":"screen"}` (the phone side never lost it) and
   restore the ch-1 draw branch + PiP.
2. **Battery/status auto-refresh**: the status strip only fills on
   REFRESH click; a lightweight poll (e.g. every 30s for battery +
   uptime) would keep the header live without user interaction.
3. **Front-cam fps in the main feed**: the flip path uses the same
   640×480 preview; bumping the `ImageAnalysis` target resolution
   (when the M51 allows) raises sharpness at an fps cost.
4. **Backpressure / token rotation on long-lived WS**: an open WS
   session survives access-token rotation (documented in SECURITY.md
   §2.8 today, not enforced — decide if the threat model cares).
5. Anything from the original helper backlog not yet wired into the
   dashboard UI (files browser panel, contacts panel).

## 3. Hard rules (unchanged)

- FROZEN sector (`docs/AGENTS.md` §3.1): `AuthManager.kt`, `TlsManager.kt`,
  security enforcement in `SimpleHttpServer.kt`, auth paths in
  `device_client.py` / `artemis.py`. ADD endpoints; never modify auth logic.
- Helpers run FGS-only: endpoints must answer with the app swiped away /
  never opened after reboot (acceptance test).
- No Activity references in helpers; no `androidx.compose` in
  helper/server layer.
- `HttpURLConnection` on the phone REQUIRES `\r\n` line endings.
- Artemis must NEVER become default SMS app or default dialer (user
  requirement — stock Samsung apps keep those roles).
- WebSocket server frames with payloads > 64 KiB MUST use the 127
  (64-bit) extended length — see AGENTS.md §2.7 for why.
- Dashboard JS is aggressively browser-cached: verify edits with a
  cache-busted URL (`?x=N`); the dashboard serves static files from
  disk, so JS/HTML edits need NO server restart.

## 4. Delivery record

- Commit + push `master`, message `v2.3.2:...`, tag `v2.3.2`, clean tree.
- `docs/AGENTS.md` §2.2/§2.8 updated; `docs/SECURITY.md` unchanged
  this phase (no security-relevant change).
