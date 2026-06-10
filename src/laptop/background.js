importScripts('config.js');

let socket = null;
const BACKEND_WS_URL = FRIDAY_CONFIG.wsUrl;

function broadcastConnectionState(isConnected) {
    chrome.tabs.query({}, (tabs) => {
        tabs.forEach((tab) => {
            if (tab.id) {
                chrome.tabs.sendMessage(tab.id, { type: "CONNECTION_STATUS", status: isConnected }).catch(() => {});
            }
        });
    });
}

function connectWebSocket() {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
        return;
    }

    socket = new WebSocket(BACKEND_WS_URL);

    socket.onopen = () => {
        console.log("[FRIDAY Background] WebSocket Connected to Hub Server.");
        broadcastConnectionState(true);

        // Register session ID with the backend
        chrome.storage.local.get(["session_id"], (result) => {
            const regMsg = {
                type: "REGISTER",
                session_id: result.session_id || null
            };
            socket.send(JSON.stringify(regMsg));
            console.log("[FRIDAY Background] Sent registration message:", regMsg);
        });
    };

    socket.onmessage = (event) => {
        try {
            const payload = JSON.parse(event.data);
            console.log("[FRIDAY Background] Received event type:", payload.type);

            if (payload.type === "REGISTERED" && payload.session_id) {
                chrome.storage.local.set({ session_id: payload.session_id }, () => {
                    console.log("[FRIDAY Background] Session registered and saved:", payload.session_id);
                });
            }

            chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                if (tabs[0] && tabs[0].id) {
                    chrome.tabs.sendMessage(tabs[0].id, payload);
                }
            });
        } catch (err) {
            console.error("[FRIDAY Background] Error parsing frame message:", err);
        }
    };

    socket.onclose = () => {
        console.log("[FRIDAY Background] Connection closed. Attempting reconnect loop...");
        broadcastConnectionState(false);
        setTimeout(connectWebSocket, 5000);
    };

    socket.onerror = (error) => {
        console.error("[FRIDAY Background] WebSocket Error encountered:", error);
        broadcastConnectionState(false);
    };
}

chrome.runtime.onInstalled.addListener(() => connectWebSocket());
chrome.runtime.onStartup.addListener(() => connectWebSocket());

chrome.action.onClicked.addListener((tab) => {
    if (tab && tab.id) {
        chrome.tabs.sendMessage(tab.id, { type: "TOGGLE_SIDEBAR" });
    }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.action === "get_connection_status") {
        const isConnected = socket && socket.readyState === WebSocket.OPEN;
        sendResponse({ status: isConnected });
        return true;
    }
    if (message.action === "send_to_backend" && socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message.data));
    }
    if (message.action === "fetch_telemetry_data") {
        chrome.storage.local.get(["session_id"], (result) => {
            const sessionId = result.session_id || "";
            const url = sessionId ? `${FRIDAY_CONFIG.telemetryUrl}?session_id=${sessionId}` : FRIDAY_CONFIG.telemetryUrl;
            
            fetch(url)
                .then(res => {
                    if (!res.ok) throw new Error("HTTP error " + res.status);
                    return res.json();
                })
                .then(data => {
                    sendResponse({ success: true, data: data });
                })
                .catch(err => {
                    sendResponse({ success: false, error: err.message });
                });
        });
        return true;
    }
});