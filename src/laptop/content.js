let shadowRoot = null;
let containerDiv = null;
let sidebarElement = null;
let handleElement = null;
let shieldBanner = null;

const FRIDAY_STATE = {
    isConnected: false,
    stressScore: null,
    activeTab: 'sense',
    isOpen: false,
    focusEfficiency: null,
    mediaHandoff: null
};

let backendData = null;
let fetchError = null;
let refreshInterval = null;

function getIconSvg(name, size = 20) {
    const paths = {
        close: '<path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>',
        sensors: '<path d="M12 2C6.48 2 2 6.48 2 12c0 2.76 1.12 5.26 2.93 7.07l1.41-1.41C4.95 16.27 4 14.24 4 12c0-4.42 3.58-8 8-8s8 3.58 8 8c0 2.24-.95 4.27-2.34 5.66l1.41 1.41C20.88 17.26 22 14.76 22 12c0-5.52-4.48-10-10-10zm0 4c-3.31 0-6 2.69-6 6 0 1.66.67 3.16 1.76 4.24l1.42-1.42C8.4 14.05 8 13.08 8 12c0-2.21 1.79-4 4-4s4 1.79 4 4c0 1.08-.4 2.05-1.18 2.82l1.42 1.42C17.33 15.16 18 13.66 18 12c0-3.31-2.69-6-6-6zm0 4c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>',
        sync: '<path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.68 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.68-2.8L5.22 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/>',
        drafts: '<path d="M21.99 8c0-.72-.37-1.35-.94-1.7L12 1 2.95 6.3c-.57.35-.95.98-.95 1.7v10c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8zm-2 0l-8 5-8-5 8-5 8 5z"/>',
        play_circle: '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"/>',
        category: '<path d="M12 2l-5.5 9h11L12 2zm0 3.84L13.93 9h-3.87L12 5.84zM17.5 13c-2.49 0-4.5 2.01-4.5 4.5s2.01 4.5 4.5 4.5 4.5-2.01 4.5-4.5-2.01-4.5-4.5-4.5zm0 7c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5zM3 13.5h8v8H3v-8zm2 2v4h4v-4H5z"/>',
        terminal: '<path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V8h16v10zm-2-1H6v-2h12v2zm-4-4H6v-2h8v2z"/>',
        language: '<path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.6 3.25 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14c-.16-.64-.26-1.31-.26-2s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.6-3.25-1.91-4.33-3.56zm2.95-8H5.08c1.09-1.65 2.5-2.96 4.33-3.56-.6 1.11-1.06 2.31-1.38 3.56zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-1.08 1.65-2.49 2.96-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"/>',
        open_in_new: '<path d="M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"/>',
        lock: '<path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"/>',
        arrow_forward: '<path d="M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z"/>',
        pending: '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 14h-2v-2h2v2zm0-4h-2V7h2v5z"/>',
        check_circle: '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>',
        hub: '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17h-2v-2h2v2zm2.07-7.75l-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H7c0-2.76 2.24-5 5-5s5 2.24 5 5c0 1.04-.42 1.99-1.07 2.75z"/>',
        alt_route: '<path d="M20 16h-5.5l-3.38-4.5 3.38-4.5H20v9zM8.5 7h5.5l3.38 4.5-3.38 4.5H8.5v-9zM3 9h4v6H3V9zm2 2v2h2v-2H5z"/>',
        psychology: '<path d="M12 2C6.48 2 2 6.48 2 12c0 2.21.72 4.25 1.93 5.92l.07.09L5 22h3.5v-2H10v-2h4v2h1.5v2H19l1-3.99.07-.09C21.28 16.25 22 14.21 22 12c0-5.52-4.48-10-10-10zm0 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z"/>',
        content_paste: '<path d="M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm5 16H7v-2h10v2zm0-4H7v-2h10v2zm0-4H7V8h10v2z"/>'
    };
    const path = paths[name] || '';
    return `<svg class="friday-svg-icon icon-${name}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="currentColor" style="display: inline-block; vertical-align: middle; flex-shrink: 0;">${path}</svg>`;
}

function initShadowDOM() {
    if (document.getElementById("friday-extension-host")) return;

    containerDiv = document.createElement("div");
    containerDiv.id = "friday-extension-host";
    document.body.appendChild(containerDiv);

    shadowRoot = containerDiv.attachShadow({ mode: "closed" });

    if (!document.querySelector("link[href*='fonts.googleapis.com/css2']")) {
        const fontLink = document.createElement("link");
        fontLink.rel = "stylesheet";
        fontLink.href = "https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&family=JetBrains+Mono:wght@400;500;700&display=swap";
        document.head.appendChild(fontLink);
    }

    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = chrome.runtime.getURL("styles.css");
    shadowRoot.appendChild(link);

    createToggleHandle();
    createSidebar();

    chrome.runtime.sendMessage({ action: "get_connection_status" }, (response) => {
        if (chrome.runtime.lastError) {
            console.log("[FRIDAY Content] Connection to background script not established yet.");
            updateConnectionUI(false);
            return;
        }
        if (response) {
            updateConnectionUI(response.status);
        }
    });
}

function createToggleHandle() {
    handleElement = document.createElement("div");
    handleElement.className = "friday-toggle-handle disconnected";
    handleElement.innerHTML = `
        <div class="status-dot"></div>
        ${getIconSvg("hub", 24)}
    `;
    handleElement.onclick = () => toggleSidebar();
    shadowRoot.appendChild(handleElement);
}

function createSidebar() {
    sidebarElement = document.createElement("div");
    sidebarElement.className = "friday-sidebar";

    sidebarElement.innerHTML = `
        <div class="friday-sidebar-header">
            <div class="friday-brand">
                <span class="friday-logo">FRIDAY</span>
                <div class="friday-conn-badge disconnected" id="friday-conn-badge">
                    <div class="friday-conn-dot"></div>
                    <span class="friday-conn-label" id="friday-conn-label">Offline</span>
                </div>
            </div>
            <button class="friday-close-btn" id="friday-close-sidebar">
                ${getIconSvg("close", 20)}
            </button>
        </div>
        
        <div class="friday-tabs">
            <button class="friday-tab-btn active" id="tab-btn-sense">
                ${getIconSvg("sensors", 18)}
                Sense
            </button>
            <button class="friday-tab-btn" id="tab-btn-continuity">
                ${getIconSvg("sync", 18)}
                Continuity
            </button>
        </div>
        
        <div class="friday-content-pane" id="friday-content-pane">
        </div>
    `;

    shadowRoot.appendChild(sidebarElement);

    shadowRoot.getElementById("friday-close-sidebar").onclick = () => toggleSidebar(false);

    const senseTabBtn = shadowRoot.getElementById("tab-btn-sense");
    const continuityTabBtn = shadowRoot.getElementById("tab-btn-continuity");

    senseTabBtn.onclick = () => switchTab('sense');
    continuityTabBtn.onclick = () => switchTab('continuity');

    switchTab('sense');
}

function loadBackendData(callback) {
    try {
        chrome.runtime.sendMessage({ action: "fetch_telemetry_data" }, (response) => {
            if (chrome.runtime.lastError) {
                fetchError = "Backend unreachable";
                backendData = null;
                if (callback) callback();
                return;
            }
            if (response && response.success && response.data && Object.keys(response.data).length > 0) {
                backendData = response.data;
                fetchError = null;
                if (backendData.stressScore !== undefined) FRIDAY_STATE.stressScore = backendData.stressScore;
                if (backendData.focusEfficiency !== undefined) FRIDAY_STATE.focusEfficiency = backendData.focusEfficiency;
                if (backendData.mediaHandoff !== undefined) FRIDAY_STATE.mediaHandoff = backendData.mediaHandoff;
            } else {
                fetchError = "Backend unreachable";
                backendData = null;
            }
            if (callback) callback();
        });
    } catch (e) {
        // Extension context invalidated (extension was reloaded) — fail gracefully
        console.warn("[FRIDAY] Extension context invalidated. Refresh the page to reconnect.");
        fetchError = "Extension reloaded — refresh page";
        backendData = null;
        if (refreshInterval) { clearInterval(refreshInterval); refreshInterval = null; }
        if (callback) callback();
    }
}

function toggleSidebar(forceOpen) {
    const shouldOpen = forceOpen !== undefined ? forceOpen : !FRIDAY_STATE.isOpen;
    FRIDAY_STATE.isOpen = shouldOpen;

    if (shouldOpen) {
        sidebarElement.classList.add("open");
        handleElement.style.right = "400px";
        switchTab(FRIDAY_STATE.activeTab);

        if (refreshInterval) clearInterval(refreshInterval);
        refreshInterval = setInterval(() => {
            if (FRIDAY_STATE.isOpen) {
                loadBackendData(() => {
                    const contentPane = shadowRoot.getElementById("friday-content-pane");
                    if (!contentPane) return;
                    if (FRIDAY_STATE.activeTab === 'sense') {
                        contentPane.innerHTML = renderSensePane();
                    } else {
                        contentPane.innerHTML = renderContinuityPane();
                        setupContinuityHooks();
                    }
                });
            }
        }, 5000);
    } else {
        sidebarElement.classList.remove("open");
        handleElement.style.right = "0";
        if (refreshInterval) {
            clearInterval(refreshInterval);
            refreshInterval = null;
        }
    }
}

function updateConnectionUI(isConnected) {
    FRIDAY_STATE.isConnected = isConnected;

    if (handleElement && sidebarElement) {
        const badge = shadowRoot.getElementById("friday-conn-badge");
        const label = shadowRoot.getElementById("friday-conn-label");

        if (isConnected) {
            handleElement.classList.remove("disconnected");
            badge.classList.remove("disconnected");
            label.textContent = "Coupled";
        } else {
            handleElement.classList.add("disconnected");
            badge.classList.add("disconnected");
            label.textContent = "Offline";
        }
    }
}

function switchTab(tabName) {
    FRIDAY_STATE.activeTab = tabName;
    const contentPane = shadowRoot.getElementById("friday-content-pane");
    if (!contentPane) return;

    const senseTabBtn = shadowRoot.getElementById("tab-btn-sense");
    const continuityTabBtn = shadowRoot.getElementById("tab-btn-continuity");

    if (tabName === 'sense') {
        senseTabBtn.classList.add("active");
        continuityTabBtn.classList.remove("active");
    } else {
        senseTabBtn.classList.remove("active");
        continuityTabBtn.classList.add("active");
    }

    contentPane.innerHTML = `
        <div style="display:flex; justify-content:center; align-items:center; height:150px; font-family:var(--font-mono); font-size:12px; color:var(--color-secondary);">
            Synchronizing ambient telemetry...
        </div>
    `;

    loadBackendData(() => {
        if (FRIDAY_STATE.activeTab !== tabName) return;
        if (tabName === 'sense') {
            contentPane.innerHTML = renderSensePane();
        } else {
            contentPane.innerHTML = renderContinuityPane();
            setupContinuityHooks();
        }
    });
}

function renderErrorPane(message) {
    return `
        <div class="friday-card-block bg-pink" style="border: 2px dashed var(--color-error); text-align: center; padding: 32px 20px;">
            <div style="font-size: 48px; margin-bottom: 16px; color: var(--color-error); display: flex; justify-content: center; align-items: center;">
                ${getIconSvg("pending", 48)}
            </div>
            <div class="friday-card-title" style="color: var(--color-error); font-size: 20px; font-weight: 900; word-break: break-all;">${message}</div>
            <p class="friday-card-body" style="margin-bottom: 0; opacity: 0.8; text-align: center;">
                The telemetry hub could not be reached. Ambient sync streams are offline.
            </p>
        </div>
    `;
}

function renderSensePane() {
    if (fetchError || !backendData) {
        return renderErrorPane(fetchError || "404 error:Server blasted");
    }

    const stressScore = backendData.stressScore !== undefined ? backendData.stressScore : 0;
    const stressState = stressScore >= 70 ? "High Burnout" : "Balanced";
    const stressClass = stressScore >= 70 ? "bg-navy" : "bg-mint";

    const focusEfficiency = backendData.focusEfficiency !== undefined ? backendData.focusEfficiency : 0;

    let activeTaskHTML = "";
    if (backendData.activeTask) {
        const t = backendData.activeTask;
        const total = t.totalSegments || 5;
        const active = t.activeSegments || 0;
        let segmentsHTML = "";
        for (let i = 0; i < total; i++) {
            const activeClass = i < active ? "active" : "";
            segmentsHTML += `<div class="friday-segment ${activeClass}"></div>`;
        }

        let checklistHTML = "";
        if (t.checklist && t.checklist.length > 0) {
            checklistHTML = `
                <div class="friday-checklist">
                    ${t.checklist.map(item => `
                        <div class="friday-check-item ${item.completed ? 'checked' : ''}">
                            ${getIconSvg(item.completed ? "check_circle" : "pending", 16)}
                            <span>${item.name}</span>
                        </div>
                    `).join('')}
                </div>
            `;
        }

        activeTaskHTML = `
            <div class="friday-card-block bg-lilac">
                <span class="friday-card-eyebrow">Active Task Pipeline</span>
                <div class="friday-card-title" style="font-size: 18px; display: flex; align-items: center; justify-content: space-between;">
                    <span>${t.title || ''}</span>
                    ${getIconSvg("alt_route", 20)}
                </div>
                <div style="display: flex; justify-content: space-between; margin: 12px 0 6px 0; font-family: var(--font-mono); font-size: 10px;">
                    <span style="color: rgba(0,0,0,0.6);">${t.completionPct || 0}% Complete</span>
                    <span style="font-weight: 700;">${t.timeLeft || ''}</span>
                </div>
                <div class="friday-segments-track">
                    ${segmentsHTML}
                </div>
                ${checklistHTML}
            </div>
        `;
    }

    return `
        <div class="friday-card-block ${stressClass}">
            <span class="friday-card-eyebrow">Emotional State</span>
            <div class="friday-metric-row">
                <div class="friday-metric-value-container">
                    <span class="friday-metric-value" id="current-stress-val">${stressScore}</span>
                    <span class="friday-metric-scale">/100</span>
                </div>
                <div class="friday-state-badge">${stressState}</div>
            </div>
            
            <div class="friday-graph-container">
                <span class="friday-graph-label">Stress Level (Last Hour)</span>
                <div class="friday-graph-bars">
                    <div class="friday-graph-bar" style="height: 40%"></div>
                    <div class="friday-graph-bar" style="height: 60%"></div>
                    <div class="friday-graph-bar" style="height: 55%"></div>
                    <div class="friday-graph-bar" style="height: 80%"></div>
                    <div class="friday-graph-bar active" style="height: ${stressScore}%"></div>
                    <div class="friday-graph-bar" style="height: 70%"></div>
                </div>
            </div>
        </div>

        <div class="friday-card-block bg-mint">
            <span class="friday-card-eyebrow">Cognitive Load</span>
            <div class="friday-card-title" style="font-size: 16px; margin-bottom: 12px; display: flex; align-items: center; gap: 8px;">
                ${getIconSvg("psychology", 18)}
                Focus Analytics
            </div>
            <p class="friday-card-body" style="margin-bottom: 20px;">
                Mental bandwidth is currently constrained by active pipeline cycles.
            </p>
            
            <span class="friday-graph-label" style="display: block; margin-bottom: 6px;">Focus Efficiency</span>
            <div class="friday-progress-container">
                <div class="friday-progress-track">
                    <div class="friday-progress-fill" style="width: ${focusEfficiency}%"></div>
                </div>
                <span class="friday-progress-pct">${focusEfficiency}%</span>
            </div>
        </div>

        ${activeTaskHTML}
    `;
}

function renderContinuityPane() {
    if (fetchError || !backendData) {
        return renderErrorPane(fetchError || "404 error:Server blasted");
    }

    let mediaHandoffHTML = "";
    if (backendData.mediaHandoff) {
        const m = backendData.mediaHandoff;
        mediaHandoffHTML = `
            <div class="friday-card-block bg-coral" style="border: 1px solid rgba(0,0,0,0.06); margin-bottom: 8px;">
                <span class="friday-card-eyebrow" style="color: rgba(0,0,0,0.7); display: flex; align-items: center; gap: 6px;">
                    ${getIconSvg("play_circle", 14)}
                    Resume Media Flow
                </span>
                <div class="friday-card-title" style="font-size: 16px;">Active YouTube Session</div>
                <p class="friday-card-body" style="font-size: 12px; margin-bottom: 16px;">
                    Pick up watching on timestamp ${Math.floor(m.playback_timestamp_seconds / 60)}m.
                </p>
                <button class="friday-pill-btn primary" id="sidebar-resume-youtube" style="width: 100%; height: 38px;">
                    Resume Workstream
                </button>
            </div>
        `;
    }

    let activeReadingHTML = "";
    if (backendData.activeReading) {
        const r = backendData.activeReading;
        activeReadingHTML = `
            <div class="friday-card-block bg-cream">
                <span class="friday-card-eyebrow">Active Reading</span>
                <div class="friday-card-title">${r.title || ''}</div>
                <p class="friday-card-body">
                    ${r.timeLabel || ''}
                </p>
                <div class="friday-progress-container" style="margin-bottom: 20px;">
                    <div class="friday-progress-track">
                        <div class="friday-progress-fill" style="width: ${r.completionPct || 0}%"></div>
                    </div>
                    <span class="friday-progress-pct" style="font-size: 13px;">${r.completionPct || 0}%</span>
                </div>
                <button class="friday-pill-btn primary" id="btn-continue-hub">
                    <span>Continue in Hub</span>
                    ${getIconSvg("arrow_forward", 16)}
                </button>
            </div>
        `;
    }

    let pendingStatesHTML = "";
    if (backendData.pendingStates && backendData.pendingStates.length > 0) {
        const items = backendData.pendingStates;
        pendingStatesHTML = `
            <div>
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                    <span class="friday-graph-label">Pending States</span>
                    <span class="friday-conn-label" style="text-transform: none;">${items.length} items</span>
                </div>
                <div class="friday-bookmarks-list">
                    ${items.map((item, idx) => `
                        <div class="friday-bookmark-card ${idx % 2 === 0 ? 'bg-mint' : 'bg-coral'}">
                            <div class="friday-bookmark-left">
                                ${getIconSvg(item.type || "drafts", 20)}
                                <div class="friday-bookmark-details">
                                    <span class="friday-bookmark-name">${item.name || ''}</span>
                                    <span class="friday-bookmark-status">${item.status || ''}</span>
                                </div>
                            </div>
                            <button class="friday-bookmark-btn" id="resume-bookmark-${idx}">RESUME</button>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    let recentAppsHTML = "";
    if (backendData.recentApps && backendData.recentApps.length > 0) {
        const apps = backendData.recentApps;
        recentAppsHTML = `
            <div>
                <span class="friday-graph-label" style="display: block; margin-bottom: 12px;">Recent Apps</span>
                <div class="friday-apps-grid">
                    ${apps.map(app => `
                        <div class="friday-app-card">
                            <div class="friday-app-icon-container" style="color: ${app.color || '#000000'};">
                                ${getIconSvg(app.icon || "category", 24)}
                            </div>
                            <div>
                                <span class="friday-app-name">${app.name || ''}</span>
                                <span class="friday-app-time" style="display: block; margin-top: 2px;">${app.time || ''}</span>
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    let recentTabsHTML = "";
    if (backendData.recentTabs && backendData.recentTabs.length > 0) {
        const tabs = backendData.recentTabs.slice(0, 3);
        recentTabsHTML = `
            <div>
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 12px;">
                    <span class="friday-graph-label">Recent Sites</span>
                    <span style="font-size: 9px; font-family: var(--font-mono); font-weight: 700; padding: 2px 7px; border-radius: 99px; background: rgba(98,0,238,0.1); color: #6200EE; letter-spacing: 0.5px;">DEVICE SWITCH</span>
                </div>
                <div class="friday-tabs-list">
                    ${tabs.map((tab, idx) => `
                        <a class="friday-tab-card" href="${tab.link || '#'}" target="_blank" style="${idx === 0 ? 'border: 1.5px solid rgba(98,0,238,0.25); background: rgba(98,0,238,0.04);' : ''}">
                            ${getIconSvg("language", 18)}
                            <div class="friday-tab-card-content">
                                <span class="friday-tab-title">${tab.title || ''}</span>
                                <span class="friday-tab-url">${tab.url || ''}</span>
                            </div>
                            <div style="display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0;">
                                ${idx === 0 ? `<span style="font-size: 8px; font-family: var(--font-mono); font-weight: 700; padding: 2px 6px; border-radius: 99px; background: rgba(98,0,238,0.15); color: #6200EE; letter-spacing: 0.5px; white-space: nowrap;">LEFT OPEN</span>` : ''}
                                ${getIconSvg("open_in_new", 14)}
                            </div>
                        </a>
                    `).join('')}
                </div>
            </div>
        `;
    }

    if (!mediaHandoffHTML && !activeReadingHTML && (!backendData.pendingStates || backendData.pendingStates.length === 0) && (!backendData.recentApps || backendData.recentApps.length === 0) && (!backendData.recentTabs || backendData.recentTabs.length === 0)) {
        return `
            <div class="friday-card-block" style="text-align: center; padding: 48px 24px; color: rgba(0,0,0,0.45); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; border: 1px dashed rgba(0,0,0,0.12); border-radius: 12px; margin-top: 20px; background: rgba(0,0,0,0.01);">
                <div style="background: rgba(0, 0, 0, 0.05); padding: 12px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #6200EE;">
                    ${getIconSvg("sync", 28)}
                </div>
                <div style="font-weight: 700; font-size: 15px; color: rgba(0,0,0,0.7); letter-spacing: -0.3px;">All Caught Up!</div>
                <p style="font-size: 12px; margin: 0; line-height: 1.5; color: rgba(0,0,0,0.5); max-width: 200px;">
                    No pending handoffs or recent activities detected. FRIDAY is coupled with your phone.
                </p>
            </div>
        `;
    }

    return `
        ${recentTabsHTML}
        ${mediaHandoffHTML}
        ${activeReadingHTML}
        ${pendingStatesHTML}
        ${recentAppsHTML}
    `;
}

function setupContinuityHooks() {
    if (fetchError || !backendData) return;

    const resumeYoutubeBtn = shadowRoot.getElementById("sidebar-resume-youtube");
    if (resumeYoutubeBtn && backendData.mediaHandoff) {
        resumeYoutubeBtn.onclick = () => {
            const data = backendData.mediaHandoff;
            window.open(`https://www.youtube.com/watch?v=${data.video_id}&t=${data.playback_timestamp_seconds}s`, "_blank");
            sendFeedback("MEDIA_HANDOFF_EXECUTED", { video_id: data.video_id });
        };
    }

    const continueHubBtn = shadowRoot.getElementById("btn-continue-hub");
    if (continueHubBtn) {
        continueHubBtn.onclick = () => {
            const url = (backendData.activeReading && backendData.activeReading.url) || "https://github.com/friday-ecosystem";
            window.open(url, "_blank");
            if (backendData.activeReading && backendData.activeReading.url) {
                sendFeedback("PAGE_HANDOFF_EXECUTED", { url: url });
            }
        };
    }

    if (backendData.pendingStates) {
        backendData.pendingStates.forEach((item, idx) => {
            const btn = shadowRoot.getElementById(`resume-bookmark-${idx}`);
            if (btn) {
                btn.onclick = () => {
                    if (item.link) {
                        window.open(item.link, "_blank");
                        if (item.type === "play_circle" || item.link.includes("youtube.com")) {
                            let vid = "";
                            try {
                                const urlObj = new URL(item.link);
                                vid = urlObj.searchParams.get("v") || "";
                            } catch (e) {}
                            sendFeedback("MEDIA_HANDOFF_EXECUTED", { video_id: vid });
                        } else {
                            sendFeedback("PAGE_HANDOFF_EXECUTED", { url: item.link });
                        }
                    }
                };
            }
        });
    }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    initShadowDOM();

    switch (message.type) {
        case "CHALLENGE_PAIRING":
            renderPairingGateway(message.pin);
            break;
        case "CLIPBOARD_SYNC":
            handleClipboardIngestion(message.snippet);
            break;
        case "MEDIA_HANDOFF":
            executeMediaHandoff(message.active_media);
            break;
        case "PAGE_HANDOFF":
            executePageHandoff(message.active_page);
            break;
        case "INTERRUPTION_SHIELD":
            toggleFocusShield(message.stress_score);
            break;
        case "SHOW_CONTINUITY":
            showContinuityToast(message);
            break;
        case "TELEMETRY_UPDATE":
            if (FRIDAY_STATE.isOpen) {
                loadBackendData(() => {
                    const contentPane = shadowRoot.getElementById("friday-content-pane");
                    if (!contentPane) return;
                    if (FRIDAY_STATE.activeTab === 'sense') {
                        contentPane.innerHTML = renderSensePane();
                    } else {
                        contentPane.innerHTML = renderContinuityPane();
                        setupContinuityHooks();
                    }
                });
            }
            break;
        case "TOGGLE_SIDEBAR":
            toggleSidebar();
            break;
        case "CONNECTION_STATUS":
            updateConnectionUI(message.status);
            break;
        default:
            console.log("[FRIDAY Content] Unrecognized packet received:", message.type);
    }
});

function renderPairingGateway(pin) {
    if (shadowRoot.getElementById("friday-pairing-modal")) return;

    const overlay = document.createElement("div");
    overlay.id = "friday-pairing-modal";
    overlay.className = "friday-modal-overlay";

    overlay.innerHTML = `
        <div class="friday-modal-card">
            <div class="friday-modal-hero">
                <div class="friday-modal-icon-circle">
                    ${getIconSvg("hub", 32)}
                </div>
                <span class="friday-modal-eyebrow">Security Protocol</span>
                <h1 class="friday-modal-title">Coupling Challenge</h1>
            </div>
            <div class="friday-modal-content">
                <p class="friday-modal-desc">
                    Enter this verification key on your primary device to authenticate the telemetry context stream.
                </p>
                <div class="friday-modal-pin">${pin.split("").join(" ")}</div>
            </div>
            <div class="friday-modal-footer">
                ${getIconSvg("lock", 16)}
                <span class="text">End-to-End Encrypted Link</span>
            </div>
        </div>
    `;

    shadowRoot.appendChild(overlay);

    setTimeout(() => overlay.remove(), 30000);
}

function handleClipboardIngestion(snippet) {
    const toast = document.createElement("div");
    toast.className = "friday-toast";
    toast.innerHTML = `
        <div class="friday-toast-header">
            ${getIconSvg("content_paste", 20)}
            Clip Shared from Phone
        </div>
        <div class="friday-toast-body">
            Do you want to copy this telemetry snippet into your laptop's clipboard buffer?
        </div>
        <div class="friday-toast-actions">
            <button id="dismiss-clip" class="friday-toast-btn secondary">Dismiss</button>
            <button id="accept-clip" class="friday-toast-btn primary">Sync Buffer</button>
        </div>
    `;

    shadowRoot.appendChild(toast);

    shadowRoot.getElementById("accept-clip").onclick = () => {
        navigator.clipboard.writeText(snippet);
        sendFeedback("CLIPBOARD_SYNC_ACCEPTED", { status: "success" });
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    shadowRoot.getElementById("dismiss-clip").onclick = () => {
        sendFeedback("CLIPBOARD_SYNC_DISMISSED", { status: "ignored" });
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    setTimeout(() => {
        if (toast.parentNode) {
            toast.style.transform = "translateX(120%)";
            toast.style.opacity = "0";
            setTimeout(() => toast.remove(), 300);
        }
    }, 10000);
}

let lastHandoffVideoId = null;
let lastHandoffTimestamp = -1;

function executeMediaHandoff(mediaData) {
    if (!mediaData) return;
    FRIDAY_STATE.mediaHandoff = mediaData;
    if (FRIDAY_STATE.isOpen && FRIDAY_STATE.activeTab === 'continuity') {
        switchTab('continuity');
    }

    if (mediaData.video_id === lastHandoffVideoId && Math.abs(mediaData.playback_timestamp_seconds - lastHandoffTimestamp) < 10) {
        return; // skip duplicate toast
    }
    lastHandoffVideoId = mediaData.video_id;
    lastHandoffTimestamp = mediaData.playback_timestamp_seconds;

    if (mediaData.provider === "youtube") {
        const toast = document.createElement("div");
        toast.className = "friday-toast";
        toast.innerHTML = `
            <div class="friday-toast-header">
                ${getIconSvg("play_circle", 20)}
                Bridge Active Playback
            </div>
            <div class="friday-toast-body">
                Resume the video lecture you left off watching on your mobile device at ${Math.floor(mediaData.playback_timestamp_seconds / 60)}m?
            </div>
            <div class="friday-toast-actions">
                <button id="dismiss-video" class="friday-toast-btn secondary">Dismiss</button>
                <button id="resume-video" class="friday-toast-btn primary">Bridge Stream</button>
            </div>
        `;

        shadowRoot.appendChild(toast);

        shadowRoot.getElementById("resume-video").onclick = () => {
            window.open(`https://www.youtube.com/watch?v=${mediaData.video_id}&t=${mediaData.playback_timestamp_seconds}s`, "_blank");
            sendFeedback("MEDIA_HANDOFF_EXECUTED", { video_id: mediaData.video_id });
            toast.style.transform = "translateX(120%)";
            toast.style.opacity = "0";
            setTimeout(() => toast.remove(), 300);
        };

        shadowRoot.getElementById("dismiss-video").onclick = () => {
            toast.style.transform = "translateX(120%)";
            toast.style.opacity = "0";
            setTimeout(() => toast.remove(), 300);
        };

        setTimeout(() => {
            if (toast.parentNode) {
                toast.style.transform = "translateX(120%)";
                toast.style.opacity = "0";
                setTimeout(() => toast.remove(), 300);
            }
        }, 12000);
    }
}

let lastHandoffUrl = null;

function executePageHandoff(pageData) {
    if (!pageData) return;
    FRIDAY_STATE.activeReading = pageData;
    if (FRIDAY_STATE.isOpen && FRIDAY_STATE.activeTab === 'continuity') {
        switchTab('continuity');
    }

    if (pageData.url === lastHandoffUrl) {
        return; // skip duplicate toast
    }
    lastHandoffUrl = pageData.url;

    const toast = document.createElement("div");
    toast.className = "friday-toast";
    toast.innerHTML = `
        <div class="friday-toast-header">
            ${getIconSvg("description", 20)}
            Resume Reading Page
        </div>
        <div class="friday-toast-body">
            Resume reading the document you left open on your mobile device: "${pageData.title || 'Active Webpage'}"?
        </div>
        <div class="friday-toast-actions">
            <button id="dismiss-page" class="friday-toast-btn secondary">Dismiss</button>
            <button id="resume-page" class="friday-toast-btn primary">Open Page</button>
        </div>
    `;

    shadowRoot.appendChild(toast);

    shadowRoot.getElementById("resume-page").onclick = () => {
        window.open(pageData.url, "_blank");
        sendFeedback("PAGE_HANDOFF_EXECUTED", { url: pageData.url });
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    shadowRoot.getElementById("dismiss-page").onclick = () => {
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    setTimeout(() => {
        if (toast.parentNode) {
            toast.style.transform = "translateX(120%)";
            toast.style.opacity = "0";
            setTimeout(() => toast.remove(), 300);
        }
    }, 12000);
}

function toggleFocusShield(stressScore) {
    FRIDAY_STATE.stressScore = stressScore;

    if (FRIDAY_STATE.isOpen) {
        switchTab(FRIDAY_STATE.activeTab);
    }

    if (stressScore >= 70) {
        if (shieldBanner) return;

        shieldBanner = document.createElement("div");
        shieldBanner.id = "friday-focus-shield-banner";
        shieldBanner.innerHTML = `
            <span class="dot"></span>
            <span class="text">Focus Preservation Active (User Stress Index: ${stressScore}%) — Non-essential notifications muted.</span>
        `;

        shadowRoot.appendChild(shieldBanner);
        document.body.classList.add("friday-desaturate-mode");
    } else {
        if (shieldBanner) {
            shieldBanner.remove();
            shieldBanner = null;
        }
        document.body.classList.remove("friday-desaturate-mode");
    }
}

function sendFeedback(eventType, trackingMetadata) {
    chrome.runtime.sendMessage({
        action: "send_to_backend",
        data: {
            type: "USER_FEEDBACK_LOOP",
            event: eventType,
            timestamp: new Date().toISOString(),
            metadata: trackingMetadata
        }
    }, () => {
        if (chrome.runtime.lastError) {
            console.warn("[FRIDAY Content] Could not send feedback to background:", chrome.runtime.lastError.message);
        }
    });
}

function showContinuityToast(message) {
    const toast = document.createElement("div");
    toast.className = "friday-toast";
    
    const content = message.content || {};
    const rawTitle = content.title || "FRIDAY Suggestion";
    const bodyText = content.message || "";
    
    // Detect device-switch leftover notifications from backend
    const isDeviceSwitchLeftover = rawTitle.toLowerCase().includes("leftover") || rawTitle.toLowerCase().includes("left open");
    const title = isDeviceSwitchLeftover ? "Device Switch · Leftover" : rawTitle;
    
    let iconName = "psychology";
    if (isDeviceSwitchLeftover) {
        iconName = "sync";
    } else if (message.condition === "high_stress" || message.condition === "burnout_risk") {
        iconName = "shield";
    } else if (message.condition === "notification_overload") {
        iconName = "notifications";
    }

    toast.innerHTML = `
        <div class="friday-toast-header">
            ${getIconSvg(iconName, 20)}
            ${title}
        </div>
        <div class="friday-toast-body">
            ${bodyText}
        </div>
        <div class="friday-toast-actions">
            <button id="dismiss-continuity" class="friday-toast-btn secondary">Dismiss</button>
            <button id="accept-continuity" class="friday-toast-btn primary">Helpful</button>
        </div>
    `;

    shadowRoot.appendChild(toast);

    shadowRoot.getElementById("accept-continuity").onclick = () => {
        chrome.runtime.sendMessage({
            action: "send_to_backend",
            data: {
                action_id: message.action_id,
                user_reaction: "helpful"
            }
        });
        if (message.url) {
            window.open(message.url, "_blank");
        }
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    shadowRoot.getElementById("dismiss-continuity").onclick = () => {
        chrome.runtime.sendMessage({
            action: "send_to_backend",
            data: {
                action_id: message.action_id,
                user_reaction: "dismissed"
            }
        });
        toast.style.transform = "translateX(120%)";
        toast.style.opacity = "0";
        setTimeout(() => toast.remove(), 300);
    };

    setTimeout(() => {
        if (toast.parentNode) {
            chrome.runtime.sendMessage({
                action: "send_to_backend",
                data: {
                    action_id: message.action_id,
                    user_reaction: "ignored"
                }
            });
            toast.style.transform = "translateX(120%)";
            toast.style.opacity = "0";
            setTimeout(() => toast.remove(), 300);
        }
    }, 15000);
}

// YouTube video tracking logic
let lastTrackedVideoId = null;
let lastTrackedTime = -1;
let lastTrackedIsPlaying = false;

function trackYouTubePlayback() {
    if (window.location.hostname.includes("youtube.com") && window.location.pathname.includes("watch")) {
        const videoElement = document.querySelector("video");
        const urlParams = new URLSearchParams(window.location.search);
        const videoId = urlParams.get("v");
        
        if (videoElement && videoId) {
            const currentTime = Math.floor(videoElement.currentTime);
            const isPlaying = !videoElement.paused && !videoElement.ended;
            const videoTitle = document.title.replace(" - YouTube", "");
            
            if (videoId !== lastTrackedVideoId || isPlaying !== lastTrackedIsPlaying || Math.abs(currentTime - lastTrackedTime) > 5) {
                lastTrackedVideoId = videoId;
                lastTrackedTime = currentTime;
                lastTrackedIsPlaying = isPlaying;
                
                chrome.runtime.sendMessage({
                    action: "send_to_backend",
                    data: {
                        type: "LAPTOP_MEDIA_UPDATE",
                        active_media: {
                            provider: "youtube",
                            video_id: videoId,
                            title: videoTitle,
                            playback_timestamp_seconds: currentTime,
                            is_playing: isPlaying,
                            timestamp: Date.now()
                        }
                    }
                });
            }
        }
    }
}

let lastTrackedUrl = null;

function trackPageContinuity() {
    if (document.hasFocus()) {
        const currentUrl = window.location.href;
        const pageTitle = document.title;
        
        if (currentUrl !== lastTrackedUrl && !currentUrl.includes("youtube.com/watch")) {
            lastTrackedUrl = currentUrl;
            
            chrome.runtime.sendMessage({
                action: "send_to_backend",
                data: {
                    type: "LAPTOP_PAGE_UPDATE",
                    active_page: {
                        url: currentUrl,
                        title: pageTitle,
                        timestamp: Date.now()
                    }
                }
            });
        }
    }
}

// Start tracking intervals (every 2 seconds)
setInterval(trackYouTubePlayback, 2000);
setInterval(trackPageContinuity, 2000);

initShadowDOM();