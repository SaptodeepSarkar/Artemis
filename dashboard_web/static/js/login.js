// Login page
document.getElementById("loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const password = document.getElementById("password").value;
    const errEl = document.getElementById("loginError");
    
    try {
        const res = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ password }),
        });
        if (res.ok) {
            window.location.href = "/";
        } else {
            const data = await res.json();
            errEl.textContent = data.detail || "Wrong password";
            errEl.classList.remove("hidden");
        }
    } catch (e) {
        errEl.textContent = "Connection error";
        errEl.classList.remove("hidden");
    }
});
