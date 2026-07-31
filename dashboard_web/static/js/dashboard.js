// Device dashboard page — status, location, camera, mic, info
let deviceKey = "";
(async function() {
    const parts = window.location.pathname.split("/");
    deviceKey = decodeURIComponent(parts[parts.length - 1]);
    document.getElementById("deviceTitle").textContent = "DEVICE: " + deviceKey;
    await loadStatus();
    loadCameras();
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
    setVal("#statusOnline .value", data.status === "ok" ? "ONLINE" : "OFFLINE");
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
}
