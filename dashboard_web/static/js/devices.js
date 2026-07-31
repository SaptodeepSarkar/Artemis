// Devices page — list, pair, forget
async function checkAuth() {
    const res = await fetch("/api/auth/status");
    const data = await res.json();
    if (!data.authenticated) {
        window.location.href = "/";
    }
}
checkAuth();

function logout() {
    fetch("/api/auth/logout", { method: "POST" }).then(() => {
        window.location.href = "/";
    });
}

function openDashboard(key) {
    window.location.href = `/dashboard/${encodeURIComponent(key)}`;
}

async function forgetDevice(key) {
    if (!confirm("Remove this device?")) return;
    await fetch(`/api/devices/${encodeURIComponent(key)}`, { method: "DELETE" });
    document.querySelector(`article[data-key="${key}"]`)?.remove();
    updateFleetStatus();
}

function pairDevice(key) {
    if (key) {
        const [host, port] = key.split(":");
        document.getElementById("pairHost").value = host;
        document.getElementById("pairKey").value = key;
    }
    document.getElementById("pairModal").classList.remove("hidden");
}

function closePairModal() {
    document.getElementById("pairModal").classList.add("hidden");
}

document.getElementById("pairForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const host = document.getElementById("pairHost").value;
    const code = document.getElementById("pairCode").value;
    const errEl = document.getElementById("pairError");
    errEl.classList.add("hidden");

    try {
        const res = await fetch("/api/devices/pair", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ host, port: 8443, code }),
        });
        const data = await res.json();
        if (data.ok) {
            window.location.reload();
        } else {
            errEl.textContent = data.error || "Pairing failed";
            errEl.classList.remove("hidden");
        }
    } catch (e) {
        errEl.textContent = "Connection error";
        errEl.classList.remove("hidden");
    }
});

async function refreshAll() {
    const cards = document.querySelectorAll("article[data-key]");
    cards.forEach(c => c.style.opacity = "0.5");
    // Re-ping all devices via backend
    const devices = await (await fetch("/api/devices")).json();
    for (const [key, dev] of Object.entries(devices)) {
        if (dev.paired) {
            await fetch("/api/devices/refresh", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ host: dev.host, port: dev.port }),
            });
        }
    }
    window.location.reload();
}

function updateFleetStatus() {
    const n = document.querySelectorAll("article[data-key]").length;
    const el = document.getElementById("fleetStatus");
    if (el) el.textContent = `FLEET_STATUS: ${n}_PAIRED`;
}

// Fleet search filter
document.getElementById("fleetSearch").addEventListener("input", (e) => {
    const q = e.target.value.toLowerCase();
    document.querySelectorAll("article[data-key]").forEach((card) => {
        card.style.display = card.textContent.toLowerCase().includes(q) ? "" : "none";
    });
});
