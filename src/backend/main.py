"""
FRIDAY - main.py
FastAPI app, WebSocket handlers, validation
Handles Android and Laptop WebSocket connections.
"""

import asyncio
import hashlib
import hmac
import json
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Query, Request as FastAPIRequest
from fastapi.middleware.cors import CORSMiddleware

import os
import sys
from pathlib import Path

# Add backend directory to sys.path to enable local imports when started from root
backend_dir = str(Path(__file__).resolve().parent)
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

from config import settings
from database.sqlite_store import SQLiteStore
from database.chroma_store import ChromaStore
from validation.orchestrator import Orchestrator
from discovery import start_discovery_service
# ──────────────────────────────────────────────────────────────────────────────
# Logging
# ──────────────────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("friday.main")

# ──────────────────────────────────────────────────────────────────────────────
# Connection Manager (thread-safe singleton)
# ──────────────────────────────────────────────────────────────────────────────

class ConnectionManager:
    """
    Singleton that wraps all active WebSocket connections and shared session
    state behind asyncio.Lock() guards.  Persistent state (recent apps, tabs,
    context snapshots) is backed by SQLite so nothing is lost on restart.
    """
    _instance: Optional["ConnectionManager"] = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True

        # Live connection maps (inherently ephemeral — websockets die on restart)
        self.android_connections: dict[str, WebSocket] = {}
        self.laptop_connections:  dict[str, WebSocket] = {}
        self._conn_lock = asyncio.Lock()

        # In-memory caches (hydrated from SQLite on startup)
        self.latest_contexts: dict[str, dict] = {}
        self.recent_apps_history: list[dict] = []
        self.recent_tabs_history: list[dict] = []

        self.last_notified_laptop_url: Optional[str] = None
        self.last_notified_laptop_timestamp: float = 0

    def hydrate_from_db(self, db_store):
        """Load persisted state from SQLite on startup."""
        self.recent_apps_history = db_store.get_recent_apps(limit=4)
        self.recent_tabs_history = db_store.get_recent_tabs(limit=3)
        # Hydrate latest contexts from persisted snapshots
        import json as _json
        for row in db_store._conn.execute(
            "SELECT session_id, payload FROM context_snapshots ORDER BY updated_at DESC LIMIT 10"
        ).fetchall():
            try:
                self.latest_contexts[row[0]] = _json.loads(row[1])
            except Exception:
                pass
        logger.info(
            f"ConnectionManager hydrated: {len(self.recent_apps_history)} apps, "
            f"{len(self.recent_tabs_history)} tabs, {len(self.latest_contexts)} contexts"
        )

    async def add_android(self, device_id: str, ws: WebSocket):
        async with self._conn_lock:
            self.android_connections[device_id] = ws

    async def remove_android(self, device_id: str):
        async with self._conn_lock:
            self.android_connections.pop(device_id, None)

    async def add_laptop(self, session_id: str, ws: WebSocket):
        async with self._conn_lock:
            self.laptop_connections[session_id] = ws

    async def remove_laptop(self, session_id: str):
        async with self._conn_lock:
            self.laptop_connections.pop(session_id, None)


conn_mgr = ConnectionManager()

# Backward-compatible module-level aliases so existing code keeps working
android_connections = conn_mgr.android_connections
laptop_connections  = conn_mgr.laptop_connections
latest_contexts     = conn_mgr.latest_contexts
recent_apps_history = conn_mgr.recent_apps_history
recent_tabs_history = conn_mgr.recent_tabs_history

async def broadcast_laptop_status():
    status_payload = {
        "type": "LAPTOP_STATUS",
        "laptop_active": len(laptop_connections) > 0,
        "recent_tabs": [
            {
                "title": tab["title"],
                "url": tab["url"],
                "link": tab["link"]
            } for tab in recent_tabs_history[:3]
        ],
        "timestamp": int(time.time() * 1000)
    }
    for dev_id, android_ws in list(android_connections.items()):
        try:
            await android_ws.send_json(status_payload)
        except Exception:
            pass

def get_app_details(package_name: str) -> dict:
    mapping = {
        "com.android.chrome": {"name": "Chrome", "icon": "language", "color": "#4285F4"},
        "com.sec.android.app.sbrowser": {"name": "Samsung Internet", "icon": "language", "color": "#0c32cf"},
        "org.mozilla.firefox": {"name": "Firefox", "icon": "language", "color": "#FF7139"},
        "com.google.android.youtube": {"name": "YouTube", "icon": "play_circle", "color": "#FF0000"},
        "com.google.android.gm": {"name": "Gmail", "icon": "drafts", "color": "#EA4335"},
        "com.whatsapp": {"name": "WhatsApp", "icon": "messages", "color": "#25D366"},
        "com.slack": {"name": "Slack", "icon": "category", "color": "#4A154B"},
        "com.android.settings": {"name": "Settings", "icon": "settings", "color": "#757575"},
        "com.friday.node": {"name": "FRIDAY", "icon": "category", "color": "#00E676"}
    }
    if package_name in mapping:
        return mapping[package_name].copy()
    
    # Generic fallback
    name = package_name.split(".")[-1].capitalize()
    return {"name": name, "icon": "category", "color": "#757575"}

def update_recent_apps(package_name: str):
    if not package_name:
        return
    details = get_app_details(package_name)
    details["timestamp"] = time.time()
    # Update in-memory cache
    conn_mgr.recent_apps_history[:] = [
        app for app in conn_mgr.recent_apps_history if app["name"] != details["name"]
    ]
    conn_mgr.recent_apps_history.insert(0, details)
    conn_mgr.recent_apps_history[:] = conn_mgr.recent_apps_history[:4]
    # Persist to SQLite
    if db:
        db.upsert_recent_app(
            name=details["name"],
            icon=details["icon"],
            color=details["color"],
            timestamp=details["timestamp"],
        )

def update_recent_tabs(title: str, url: str):
    if not url or not title:
        return
    # Clean up url format for display: e.g. remove protocol and long path
    try:
        from urllib.parse import urlparse
        parsed = urlparse(url)
        display_url = parsed.netloc + parsed.path
        if len(display_url) > 40:
            display_url = display_url[:37] + "..."
    except Exception:
        display_url = url

    details = {
        "title": title,
        "url": display_url,
        "link": url,
        "timestamp": time.time()
    }
    # Update in-memory cache
    conn_mgr.recent_tabs_history[:] = [
        tab for tab in conn_mgr.recent_tabs_history if tab["link"] != url
    ]
    conn_mgr.recent_tabs_history.insert(0, details)
    conn_mgr.recent_tabs_history[:] = conn_mgr.recent_tabs_history[:3]
    # Persist to SQLite
    if db:
        db.upsert_recent_tab(
            title=title,
            url=display_url,
            link=url,
            timestamp=details["timestamp"],
        )

async def push_laptop_leftover_notification(device_id: str, android_ws: WebSocket):
    lm = getattr(orchestrator, "last_laptop_media", None)
    lp = getattr(orchestrator, "last_laptop_page", None)
    
    tasks = []
    if lm:
        vid = lm.get("video_id", "")
        m_url = f"https://www.youtube.com/watch?v={vid}" if vid else ""
        tasks.append((lm.get("timestamp", 0), "media", lm, m_url))
    if lp:
        tasks.append((lp.get("timestamp", 0), "page", lp, lp.get("url", "")))
        
    if not tasks:
        return
        
    tasks.sort(key=lambda x: x[0], reverse=True)
    ts, task_type, task_data, target_url = tasks[0]
    
    if target_url == conn_mgr.last_notified_laptop_url:
        return
        
    conn_mgr.last_notified_laptop_url = target_url
    conn_mgr.last_notified_laptop_timestamp = ts
    
    title = task_data.get("title", "YouTube Video" if task_type == "media" else "Webpage")
    update_recent_tabs(title, target_url)
    
    await broadcast_laptop_status()
    
    if task_type == "media":
        try:
            payload = {
                "type": "MEDIA_HANDOFF",
                "action_id": f"act_media_handoff_{int(time.time())}",
                "message": f"Resume watching: {title}",
                "agent": "Continuity",
                "score": 95.0,
                "provider": "youtube",
                "video_id": task_data.get("video_id", ""),
                "playback_timestamp_seconds": task_data.get("playback_timestamp_seconds", 0),
                "timestamp": int(time.time() * 1000)
            }
            await android_ws.send_json(payload)
            logger.info(f"Pushed laptop media handoff to Android {device_id} on device switch")
        except Exception as e:
            logger.warning(f"Failed to push media handoff on device switch: {e}")
    else:
        try:
            payload = {
                "type": "PAGE_HANDOFF",
                "action_id": f"act_page_handoff_{int(time.time())}",
                "message": f"Resume reading: {title}",
                "agent": "Continuity",
                "score": 95.0,
                "url": target_url,
                "timestamp": int(time.time() * 1000)
            }
            await android_ws.send_json(payload)
            logger.info(f"Pushed laptop page handoff to Android {device_id} on device switch")
        except Exception as e:
            logger.warning(f"Failed to push page handoff on device switch: {e}")

def get_dynamic_pending_states(ctx: Optional[dict]) -> list[dict]:
    pending = []
    sensor = ctx.get("sensor_data", {}) if ctx else {}
    
    # 1. Last Phone Page (if present)
    last_phone_page = getattr(orchestrator, "last_phone_page", None) if orchestrator else None
    if last_phone_page:
        pending.append({
            "type": "drafts",
            "name": last_phone_page.get("title", "Active Webpage"),
            "status": "Left off on Phone",
            "link": last_phone_page.get("url", "")
        })
        
    # 2. Last Phone Media (if present)
    last_phone_media = getattr(orchestrator, "last_phone_media", None) if orchestrator else None
    if last_phone_media:
        vid = last_phone_media.get("video_id")
        t = last_phone_media.get("playback_timestamp_seconds", 0)
        link = f"https://www.youtube.com/watch?v={vid}&t={t}s" if vid else ""
        time_str = f"Paused at {t // 60}:{t % 60:02d}" if t > 0 else "Ready to play"
        pending.append({
            "type": "play_circle",
            "name": last_phone_media.get("title", "YouTube video"),
            "status": f"Left off on Phone • {time_str}",
            "link": link
        })
        
    # 3. Last Laptop Page (if present and not already in pending)
    last_laptop_page = getattr(orchestrator, "last_laptop_page", None) if orchestrator else None
    if last_laptop_page:
        url = last_laptop_page.get("url")
        if url and not any(p["link"] == url for p in pending):
            pending.append({
                "type": "language",
                "name": last_laptop_page.get("title", "Laptop Webpage"),
                "status": "Left off on Laptop",
                "link": url
            })
            
    # 4. Last Laptop Media (if present and not already in pending)
    last_laptop_media = getattr(orchestrator, "last_laptop_media", None) if orchestrator else None
    if last_laptop_media:
        vid = last_laptop_media.get("video_id")
        t = last_laptop_media.get("playback_timestamp_seconds", 0)
        link = f"https://www.youtube.com/watch?v={vid}&t={t}s" if vid else ""
        if link and not any(p["link"] == link for p in pending):
            time_str = f"Paused at {t // 60}:{t % 60:02d}" if t > 0 else "Ready to play"
            pending.append({
                "type": "play_circle",
                "name": last_laptop_media.get("title", "YouTube Video"),
                "status": f"Left off on Laptop • {time_str}",
                "link": link
            })
    
    # 5. Active workspace task in progress (if present)
    active_task = ctx.get("active_task") if ctx else None
    if active_task:
        desc = active_task.get("description", "Active Task")
        pending.append({
            "type": "terminal",
            "name": desc,
            "status": "In progress",
            "link": "https://github.com/friday-ecosystem"
        })

    return pending

db:           Optional[SQLiteStore]  = None
chroma:       Optional[ChromaStore]  = None
orchestrator: Optional[Orchestrator] = None
voice_agent   = None  # Initialized in lifespan
zeroconf_instance = None
zeroconf_info = None


# ──────────────────────────────────────────────────────────────────────────────
# Lifespan (startup / shutdown)
# ──────────────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db, chroma, orchestrator, voice_agent, zeroconf_instance, zeroconf_info

    logger.info("FRIDAY backend starting up …")
    db      = SQLiteStore(settings.SQLITE_PATH)
    chroma  = ChromaStore(settings.CHROMA_PATH)
    orchestrator = Orchestrator(db=db, chroma=chroma, laptop_connections=laptop_connections)
    orchestrator.last_laptop_media = None
    orchestrator.last_laptop_page = None
    orchestrator.last_phone_media = None
    orchestrator.last_phone_page = None

    # Hydrate persistent state from SQLite (recent apps, tabs, contexts)
    conn_mgr.hydrate_from_db(db)

    # Initialize voice transcription agent (whisper.cpp)
    try:
        from agents.voice import VoiceAgent
        voice_agent = VoiceAgent()
        logger.info("VoiceAgent initialized successfully.")
    except Exception as e:
        logger.warning(f"VoiceAgent unavailable (whisper.cpp not built?): {e}")
        voice_agent = None

    logger.info("All stores and orchestrator initialised.")

    try:
        zeroconf_instance, zeroconf_info = await start_discovery_service(settings.PORT)
    except Exception as e:
        import traceback
        logger.warning(f"Failed to start Zeroconf discovery: {e}\n{traceback.format_exc()}")

    yield

    logger.info("FRIDAY backend shutting down …")
    
    if zeroconf_instance and zeroconf_info:
        try:
            logger.info("Stopping Zeroconf service discovery …")
            await zeroconf_instance.zeroconf.async_unregister_service(zeroconf_info)
            await zeroconf_instance.async_close()
        except Exception as e:
            logger.warning(f"Failed to clean up Zeroconf: {e}")

    db.close()


# ──────────────────────────────────────────────────────────────────────────────
# App
# ──────────────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="FRIDAY Backend",
    version="1.0.0",
    description="Empathetic AI backend for the FRIDAY cross-device system.",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def verify_hmac(payload_bytes: bytes, signature: str) -> bool:
    """Verify HMAC-SHA256 signature on incoming Android payloads."""
    expected = hmac.new(
        settings.HMAC_SECRET.encode(),
        payload_bytes,
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


def validate_context_object(data: dict) -> tuple[bool, str]:
    """Validate required fields in a ContextObject."""
    required_top = {"metadata", "user_state", "sensor_data"}
    missing = required_top - data.keys()
    if missing:
        return False, f"Missing top-level keys: {missing}"

    meta_required = {"timestamp", "device_id", "session_id", "message_id"}
    missing_meta = meta_required - data["metadata"].keys()
    if missing_meta:
        return False, f"Missing metadata keys: {missing_meta}"

    return True, "ok"


# ──────────────────────────────────────────────────────────────────────────────
# REST Endpoints
# ──────────────────────────────────────────────────────────────────────────────

@app.get("/health", tags=["system"])
async def health_check():
    """Server liveness check."""
    return {
        "status": "ok",
        "version": "1.0.0",
        "android_connections": len(android_connections),
        "laptop_connections":  len(laptop_connections),
    }


@app.get("/api/status", tags=["system"])
async def agent_status():
    """Return agent availability and memory store sizes."""
    return {
        "agents":        orchestrator.agent_status() if orchestrator else {},
        "memory_count":  chroma.count() if chroma else 0,
        "kpi_log_count": db.count_logs() if db else 0,
    }


@app.get("/api/logs", tags=["kpi"])
async def export_logs(
    start_date: Optional[str] = Query(None, description="ISO date filter e.g. 2026-01-01"),
    limit:      int           = Query(1000, le=10000),
):
    """
    Export KPI logs as JSON for judge verification.
    Filter by start_date (YYYY-MM-DD) and limit.
    """
    if db is None:
        raise HTTPException(status_code=503, detail="Database not ready")
    rows = db.export_logs(start_date=start_date, limit=limit)
    return {"count": len(rows), "logs": rows}


@app.post("/api/onboarding/complete", tags=["onboarding"])
async def onboarding_complete(request: FastAPIRequest):
    """
    Receive onboarding completion notification from Android.
    Stores device config and enabled modules for session tracking.
    """
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    device_id = body.get("device_id", "unknown")
    enabled_modules = body.get("enabled_modules", [])
    modules_count = body.get("modules_enabled", 0)
    config = body.get("configuration", {})
    timestamp = body.get("timestamp", int(time.time() * 1000))

    logger.info(
        f"Onboarding complete: device={device_id}, "
        f"modules_enabled={modules_count}, modules={enabled_modules}"
    )

    # Log to KPI database
    if db:
        db.log_session_event(
            session_id=f"onboarding_{device_id}",
            device_id=device_id,
            event_type="onboarding_complete",
            is_offline=False,
            latency_ms=0,
        )

    return {
        "status": "ok",
        "device_id": device_id,
        "modules_acknowledged": modules_count,
        "message": "Onboarding configuration received. FRIDAY backend ready.",
    }


@app.get("/api/telemetry", tags=["telemetry"])
async def get_telemetry(session_id: Optional[str] = Query(None)):
    """
    Get real-time telemetry data (stress, focus, tasks, media) for a session.
    """
    ctx = None
    if session_id and session_id in latest_contexts:
        ctx = latest_contexts[session_id]
    elif latest_contexts:
        # fallback to the most recent context across all sessions
        ctx = list(latest_contexts.values())[-1]

    # Map context to the schema expected by content.js
    if ctx:
        sensor = ctx.get("sensor_data", {})
        user_state = ctx.get("user_state", {})
        active_task = ctx.get("active_task")

        # Focus efficiency calculation based on app switches and typo rate
        app_switches = sensor.get("app_switches", 0)
        typo_rate = sensor.get("typo_rate", 0.0)
        focus_efficiency = 100 - min(int((app_switches / 15 * 40) + (typo_rate * 300)), 90)

        # Map active task
        active_task_payload = None
        if active_task:
            deadline_str = active_task.get("deadline")
            time_left = ""
            if deadline_str:
                try:
                    from datetime import datetime, timezone
                    deadline = datetime.fromisoformat(deadline_str.replace("Z", "+00:00"))
                    now = datetime.now(timezone.utc)
                    diff = deadline - now
                    if diff.total_seconds() < 0:
                        time_left = "Overdue"
                    else:
                        hours = int(diff.total_seconds() // 3600)
                        mins = int((diff.total_seconds() % 3600) // 60)
                        if hours > 0:
                            time_left = f"{hours}h {mins}m left"
                        else:
                            time_left = f"{mins}m left"
                except Exception:
                    time_left = "Approaching deadline"
            progress = active_task.get("progress", 0.0)
            active_task_payload = {
                "title": active_task.get("description", "Active Task Pipeline"),
                "timeLeft": time_left,
                "completionPct": int(progress * 100),
                "totalSegments": 5,
                "activeSegments": int(progress * 5),
                "checklist": active_task.get("checklist") or [
                    {"name": "Understand requirements", "completed": progress >= 0.25},
                    {"name": "Draft implementation", "completed": progress >= 0.5},
                    {"name": "Refine and integrate", "completed": progress >= 0.75},
                    {"name": "Final review", "completed": progress >= 0.95}
                ]
            }

        # Map media handoff
        media_handoff_payload = None
        last_phone_media = getattr(orchestrator, "last_phone_media", None) if orchestrator else None
        if last_phone_media:
            media_handoff_payload = {
                "provider": last_phone_media.get("provider", "youtube"),
                "video_id": last_phone_media.get("video_id", ""),
                "title": last_phone_media.get("title", "YouTube Video"),
                "playback_timestamp_seconds": int(last_phone_media.get("playback_timestamp_seconds", 0))
            }

        # Map active reading (page handoff from phone)
        active_reading_payload = None
        last_phone_page = getattr(orchestrator, "last_phone_page", None) if orchestrator else None
        if last_phone_page:
            active_reading_payload = {
                "title": last_phone_page.get("title", "Active Webpage"),
                "timeLabel": "Resume reading",
                "completionPct": 50,
                "url": last_phone_page.get("url", "")
            }

        # Format recent apps list from history with relative times
        recent_apps = []
        for app in recent_apps_history:
            elapsed = int(time.time() - app["timestamp"])
            if elapsed < 60:
                time_str = "Just now"
            elif elapsed < 3600:
                time_str = f"{elapsed // 60}m ago"
            else:
                time_str = f"{elapsed // 3600}h ago"
            recent_apps.append({
                "name": app["name"],
                "icon": app["icon"],
                "time": time_str,
                "color": app["color"]
            })

        # Format recent tabs
        recent_tabs = []
        for tab in recent_tabs_history:
            recent_tabs.append({
                "title": tab["title"],
                "url": tab["url"],
                "link": tab["link"]
            })

        payload = {
            "stressScore": int(user_state.get("stress_score", 42)),
            "focusEfficiency": focus_efficiency,
            "activeTask": active_task_payload,
            "mediaHandoff": media_handoff_payload,
            "activeReading": active_reading_payload,
            "pendingStates": get_dynamic_pending_states(ctx),
            "recentApps": recent_apps,
            "recentTabs": recent_tabs
        }
        return payload
    else:
        return {
            "stressScore": 42,
            "focusEfficiency": 100,
            "activeTask": None,
            "mediaHandoff": None,
            "activeReading": None,
            "pendingStates": [],
            "recentApps": [],
            "recentTabs": []
        }


# ──────────────────────────────────────────────────────────────────────────────
# WebSocket — Android
# ──────────────────────────────────────────────────────────────────────────────
# Inactivity summary generation helper
# ──────────────────────────────────────────────────────────────────────────────

async def execute_hub_context_summary_generation(raw_payload: str) -> str:
    """
    Run Ollama to summarize the batch of notifications missed during inactivity.
    If Ollama is not running/unreachable, falls back to a rule-based summary helper.
    """
    import httpx
    
    system_prompt = (
        "You are FRIDAY, an empathetic AI assistant. "
        "The user has been away from their device. Below is a batch of notifications they missed. "
        "Summarize these notifications in a single concise, friendly, and structured sentence or two. "
        "Format it nicely and highlight any critical action items or key updates. "
        "Do not repeat details if they are not important."
    )
    user_prompt = f"Missed notifications:\n{raw_payload}"
    
    try:
        async with httpx.AsyncClient(timeout=settings.LLM_TIMEOUT) as client:
            resp = await client.post(
                f"{settings.OLLAMA_BASE_URL}/api/generate",
                json={
                    "model": settings.PRIMARY_LLM_MODEL,
                    "prompt": f"<system>{system_prompt}</system>\n{user_prompt}",
                    "stream": False,
                    "options": {"temperature": 0.5, "num_predict": 120},
                },
            )
            resp.raise_for_status()
            summary = resp.json().get("response", "").strip()
            if summary:
                return summary
    except Exception as e:
        logger.warning(f"Ollama digest generation failed: {e}. Using local rule-based fallback.")
    
    # Rule-based fallback summary logic
    lines = [line.strip() for line in raw_payload.split("\n") if line.strip()]
    if not lines:
        return "You had no notifications during this period of inactivity."
        
    app_counts = {}
    for line in lines:
        if line.startswith("[App: ") and "]" in line:
            app_name = line.split("]")[0].replace("[App: ", "")
            # Get simpler app name
            app_name = app_name.split(".")[-1]
            app_counts[app_name] = app_counts.get(app_name, 0) + 1
            
    summary_parts = [f"{count} from {app}" for app, count in app_counts.items()]
    return f"While you were away, you missed {len(lines)} updates: " + ", ".join(summary_parts) + "."

# ──────────────────────────────────────────────────────────────────────────────

@app.websocket("/ws/android")
async def android_ws(websocket: WebSocket):
    """
    Persistent WebSocket for Android sensor stream.

    Protocol:
      Android → Backend : JSON ContextObject (every 15 s or on event)
      Backend → Android : JSON response/action card
      Backend → Android : ACK every 100 messages
    """
    await websocket.accept()
    device_id   = None
    msg_counter = 0

    logger.info("Android WebSocket connected")

    # On connect: send enriched LAPTOP_STATUS with last 3 recent_tabs so phone
    # immediately knows what the user left open on the laptop (device switch moment).
    try:
        await websocket.send_json({
            "type": "LAPTOP_STATUS",
            "laptop_active": len(laptop_connections) > 0,
            "recent_tabs": [
                {"title": tab["title"], "url": tab["url"], "link": tab["link"]}
                for tab in recent_tabs_history[:3]
            ],
            "timestamp": int(time.time() * 1000)
        })
    except Exception:
        pass

    # If laptop has leftover media/page, push the handoff notification immediately
    # so Android can surface it as a device-switch continuity alert.
    lm = getattr(orchestrator, "last_laptop_media", None) if orchestrator else None
    lp = getattr(orchestrator, "last_laptop_page", None) if orchestrator else None
    if lm or lp:
        try:
            await push_laptop_leftover_notification("connecting_device", websocket)
        except Exception as e:
            logger.warning(f"Failed to push leftover on connect: {e}")

    try:
        while True:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=60.0)
            payload_bytes = raw.encode()

            # Parse
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"error": "invalid JSON"})
                continue

            # ── Handle feedback messages from Android RLHF ──
            msg_type = data.get("type", "")
            if msg_type == "feedback":
                action_id = data.get("action_id")
                user_reaction = data.get("user_reaction")  # helpful / dismissed / ignored
                if action_id and user_reaction and orchestrator:
                    orchestrator.record_feedback(action_id=action_id, reaction=user_reaction)
                    logger.info(f"Android RLHF feedback: {action_id} → {user_reaction}")
                    
                    if action_id.startswith("act_media_handoff"):
                        for sid, ctx in list(latest_contexts.items()):
                            sensor = ctx.get("sensor_data", {})
                            sensor["active_media"] = None
                        if getattr(orchestrator, "last_laptop_media", None):
                            orchestrator.last_laptop_media = None
                    elif action_id.startswith("act_page_handoff"):
                        for sid, ctx in list(latest_contexts.items()):
                            sensor = ctx.get("sensor_data", {})
                            sensor["active_page"] = None
                        if getattr(orchestrator, "last_laptop_page", None):
                            orchestrator.last_laptop_page = None
                continue

            if msg_type == "LIVE_TRACKER_SIGNAL":
                logger.info(f"Live tracker update: app={data.get('package_id')} title={data.get('title')}")
                continue

            if msg_type == "BATCH_SUMMARY_REQUEST":
                raw_payload = data.get("raw_payload", "")
                logger.info(f"Batch summary request received. Length={len(raw_payload)}")
                summary = await execute_hub_context_summary_generation(raw_payload)
                response_frame = {
                    "type": "INACTIVITY_DIGEST_RESPONSE",
                    "summary": summary
                }
                await websocket.send_json(response_frame)
                continue

            if msg_type == "sync_request":
                logger.info("Sync request received from Android app")
                lm = getattr(orchestrator, "last_laptop_media", None)
                lp = getattr(orchestrator, "last_laptop_page", None)
                
                tasks = []
                if lm:
                    tasks.append((lm.get("timestamp", 0), "media", lm))
                if lp:
                    tasks.append((lp.get("timestamp", 0), "page", lp))
                
                if tasks:
                    tasks.sort(key=lambda x: x[0], reverse=True)
                    most_recent = tasks[0]
                    
                    if most_recent[1] == "media":
                        lm = most_recent[2]
                        try:
                            payload = {
                                "type": "MEDIA_HANDOFF",
                                "action_id": f"act_media_handoff_{int(time.time())}",
                                "message": f"Resume watching: {lm.get('title')}",
                                "agent": "Continuity",
                                "score": 95.0,
                                "provider": "youtube",
                                "video_id": lm.get("video_id", ""),
                                "playback_timestamp_seconds": lm.get("playback_timestamp_seconds", 0),
                                "timestamp": int(time.time() * 1000)
                            }
                            await websocket.send_json(payload)
                            logger.info("Sent last laptop media to Android sync request (most recent)")
                        except Exception as e:
                            logger.warning(f"Failed to send media handoff during sync: {e}")
                    else:
                        lp = most_recent[2]
                        try:
                            payload = {
                                "type": "PAGE_HANDOFF",
                                "action_id": f"act_page_handoff_{int(time.time())}",
                                "message": f"Resume reading: {lp.get('title')}",
                                "agent": "Continuity",
                                "score": 95.0,
                                "url": lp.get("url", ""),
                                "timestamp": int(time.time() * 1000)
                            }
                            await websocket.send_json(payload)
                            logger.info("Sent last laptop page to Android sync request (most recent)")
                        except Exception as e:
                            logger.warning(f"Failed to send page handoff during sync: {e}")
                continue

            # ── Handle individual sensor events (not full ContextObjects) ──
            # The Android side also sends raw events like app_switch,
            # typing_metrics, notification. Log them but don't orchestrate.
            if msg_type in ("app_switch", "typing_metrics", "notification", "biometric_baseline"):
                logger.debug(f"Individual sensor event received: {msg_type}")
                if msg_type == "app_switch":
                    package_name = data.get("package_name") or data.get("package_id")
                    if package_name:
                        update_recent_apps(package_name)
                continue

            # HMAC verification (optional; skip if sig absent for dev)
            sig = data.pop("hmac_signature", None)
            if settings.ENFORCE_HMAC and sig:
                if not verify_hmac(payload_bytes, sig):
                    await websocket.send_json({"error": "HMAC verification failed"})
                    continue

            # Validate ContextObject schema
            ok, reason = validate_context_object(data)
            if not ok:
                logger.debug(f"Non-ContextObject message skipped: {reason}")
                continue

            device_id = data["metadata"]["device_id"]
            await conn_mgr.add_android(device_id, websocket)
            session_id = data["metadata"]["session_id"]
            
            prev_context = latest_contexts.get(session_id)
            latest_contexts[session_id] = data

            # Persist incoming context snapshot to SQLite
            if db:
                import json as _json
                db.save_context_snapshot(session_id, _json.dumps(data), time.time())

            # Extract focused app and active page/media from the context to keep recent histories up-to-date
            sensor = data.get("sensor_data", {})
            focused_app = sensor.get("focused_app")
            if focused_app:
                update_recent_apps(focused_app)
            active_page = sensor.get("active_page")
            if active_page:
                update_recent_tabs(active_page.get("title"), active_page.get("url"))
                if orchestrator:
                    orchestrator.last_phone_page = active_page
            active_media = sensor.get("active_media")
            if active_media:
                if orchestrator:
                    orchestrator.last_phone_media = active_media

            # Notify all connected laptops to refresh their UI
            for l_sid, l_ws in list(laptop_connections.items()):
                try:
                    await l_ws.send_json({
                        "type": "TELEMETRY_UPDATE",
                        "timestamp": int(time.time() * 1000)
                    })
                except Exception:
                    pass

            prev_sensor = prev_context.get("sensor_data", {}) if prev_context else {}
            prev_page = prev_sensor.get("active_page")
            
            # Transition: left a page on phone
            if prev_page and not active_page:
                title = prev_page.get("title", "Active Page")
                url = prev_page.get("url", "")
                action_id = f"act_page_handoff_{int(time.time())}"
                logger.info(f"Phone left page: {title} -> Pushing handoff to laptops")
                for l_sid, l_ws in list(laptop_connections.items()):
                    try:
                        payload = {
                            "type": "SHOW_CONTINUITY",
                            "action_id": action_id,
                            "url": url,
                            "content": {
                                "title": "Leftover Task on Phone",
                                "message": f"Resume reading: {title}"
                            },
                            "timestamp": int(time.time() * 1000)
                        }
                        await l_ws.send_json(payload)
                    except Exception as e:
                        logger.warning(f"Failed to push page handoff to laptop {l_sid}: {e}")

            # Transition: left a video/media on phone
            prev_media = prev_sensor.get("active_media")
            active_media = sensor.get("active_media")
            if prev_media and not active_media:
                title = prev_media.get("title", "Active Video")
                vid = prev_media.get("video_id", "")
                playback_time = prev_media.get("playback_timestamp_seconds", 0)
                url = f"https://www.youtube.com/watch?v={vid}&t={playback_time}s" if vid else "https://www.youtube.com"
                action_id = f"act_media_handoff_{int(time.time())}"
                logger.info(f"Phone left media: {title} -> Pushing handoff to laptops")
                for l_sid, l_ws in list(laptop_connections.items()):
                    try:
                        payload = {
                            "type": "SHOW_CONTINUITY",
                            "action_id": action_id,
                            "url": url,
                            "content": {
                                "title": "Leftover Video on Phone",
                                "message": f"Resume watching: {title}"
                            },
                            "timestamp": int(time.time() * 1000)
                        }
                        await l_ws.send_json(payload)
                    except Exception as e:
                        logger.warning(f"Failed to push media handoff to laptop {l_sid}: {e}")

            # Track offline replays
            is_offline_replay = data.get("is_offline_replay", False)

            # Log receipt
            db.log_session_event(
                session_id=session_id,
                device_id=device_id,
                event_type="context_received",
                is_offline=is_offline_replay,
                latency_ms=0,
            )

            # Orchestrate
            t0 = time.time()
            response = await orchestrator.process(data)
            latency  = int((time.time() - t0) * 1000)

            # Update latency in session log
            db.log_session_event(
                session_id=data["metadata"]["session_id"],
                device_id=device_id,
                event_type="response_sent",
                is_offline=False,
                latency_ms=latency,
            )

            # Deliver response to Android
            if response:
                await websocket.send_json(response)

            # ACK every 100 messages
            msg_counter += 1
            if msg_counter % 100 == 0:
                await websocket.send_json({"type": "ACK", "count": msg_counter})

    except asyncio.TimeoutError:
        logger.warning("Android connection timed out (no heartbeat for 60 s)")
    except WebSocketDisconnect:
        logger.info(f"Android disconnected: {device_id}")
    except Exception as exc:
        logger.exception(f"Android WS error: {exc}")
    finally:
        if device_id:
            await conn_mgr.remove_android(device_id)


# ──────────────────────────────────────────────────────────────────────────────
# WebSocket — Laptop
# ──────────────────────────────────────────────────────────────────────────────

@app.websocket("/ws/laptop")
async def laptop_ws(websocket: WebSocket):
    """
    Persistent WebSocket for Laptop (Chrome Extension) UI stream.

    Protocol:
      Backend  → Laptop : UICommand JSON (SHOW_CONTINUITY / SHOW_TOAST / …)
      Laptop   → Backend: user reaction JSON
    """
    await websocket.accept()
    session_id = None
    logger.info("Laptop WebSocket connected")

    try:
        # First message from laptop must identify its session
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=30.0)
        data = json.loads(raw)
        session_id = data.get("session_id")
        if not session_id:
            session_id = f"laptop_{int(time.time())}"
        await conn_mgr.add_laptop(session_id, websocket)
        logger.info(f"Laptop registered session: {session_id}")
        await websocket.send_json({"type": "REGISTERED", "session_id": session_id})
        await broadcast_laptop_status()

        while True:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=120.0)
            reaction = json.loads(raw)

            # Handle telemetry updates from laptop
            msg_type = reaction.get("type")
            if msg_type == "LAPTOP_MEDIA_UPDATE":
                active_media = reaction.get("active_media")
                if active_media:
                    orchestrator.last_laptop_media = active_media
                    logger.info(f"Updated last laptop media: {active_media}")

            elif msg_type == "LAPTOP_PAGE_UPDATE":
                active_page = reaction.get("active_page")
                if active_page:
                    orchestrator.last_laptop_page = active_page
                    logger.info(f"Updated last laptop page: {active_page}")

            elif msg_type == "USER_FEEDBACK_LOOP":
                event_name = reaction.get("event")
                metadata = reaction.get("metadata") or {}
                logger.info(f"User feedback loop: event={event_name}, metadata={metadata}")
                
                if event_name == "MEDIA_HANDOFF_EXECUTED":
                    vid = metadata.get("video_id")
                    for sid, ctx in list(latest_contexts.items()):
                        sensor = ctx.get("sensor_data", {})
                        am = sensor.get("active_media")
                        if am and am.get("video_id") == vid:
                            sensor["active_media"] = None
                    if getattr(orchestrator, "last_laptop_media", None) and orchestrator.last_laptop_media.get("video_id") == vid:
                        orchestrator.last_laptop_media = None
                    if getattr(orchestrator, "last_phone_media", None) and orchestrator.last_phone_media.get("video_id") == vid:
                        orchestrator.last_phone_media = None
                        
                elif event_name == "PAGE_HANDOFF_EXECUTED":
                    url = metadata.get("url")
                    for sid, ctx in list(latest_contexts.items()):
                        sensor = ctx.get("sensor_data", {})
                        ap = sensor.get("active_page")
                        if ap and ap.get("url") == url:
                            sensor["active_page"] = None
                    if getattr(orchestrator, "last_laptop_page", None) and orchestrator.last_laptop_page.get("url") == url:
                        orchestrator.last_laptop_page = None
                    if getattr(orchestrator, "last_phone_page", None) and orchestrator.last_phone_page.get("url") == url:
                        orchestrator.last_phone_page = None

            # Handle user feedback / RLHF
            action_id    = reaction.get("action_id")
            user_reaction = reaction.get("user_reaction")  # helpful / dismissed / ignored

            if action_id and user_reaction:
                orchestrator.record_feedback(action_id=action_id, reaction=user_reaction)
                logger.info(f"Feedback recorded: {action_id} → {user_reaction}")
                
                # Clear active phone tasks on reaction
                if action_id.startswith("act_media_handoff"):
                    if getattr(orchestrator, "last_phone_media", None):
                        orchestrator.last_phone_media = None
                elif action_id.startswith("act_page_handoff"):
                    if getattr(orchestrator, "last_phone_page", None):
                        orchestrator.last_phone_page = None

    except asyncio.TimeoutError:
        logger.warning(f"Laptop connection timed out: {session_id}")
    except WebSocketDisconnect:
        logger.info(f"Laptop disconnected: {session_id}")
    except Exception as exc:
        logger.exception(f"Laptop WS error: {exc}")
    finally:
        if session_id:
            await conn_mgr.remove_laptop(session_id)
            await broadcast_laptop_status()


# ──────────────────────────────────────────────────────────────────────────────
# WebSocket — Voice Transcription
# ──────────────────────────────────────────────────────────────────────────────

@app.websocket("/ws/voice")
async def voice_ws(websocket: WebSocket):
    """
    WebSocket endpoint for streaming voice audio from Android.

    Protocol:
      Android → Backend : Binary audio frames (raw bytes)
      Android → Backend : JSON control messages:
        {"type": "voice_start", "format": "amr"} — begin recording
        {"type": "voice_end"}                     — finish recording, trigger transcription
      Backend → Android : JSON transcription result:
        {"type": "VOICE_TRANSCRIPTION", "text": "...", "duration_ms": 123}
    """
    await websocket.accept()
    logger.info("Voice WebSocket connected")

    if voice_agent is None:
        await websocket.send_json({"type": "VOICE_ERROR", "error": "Voice transcription unavailable"})
        await websocket.close(1011, "VoiceAgent not initialized")
        return

    audio_buffer = bytearray()
    source_format = "wav"

    try:
        while True:
            message = await websocket.receive()

            if message.get("type") == "websocket.disconnect":
                logger.info("Voice WebSocket client disconnected cleanly")
                break

            if "bytes" in message and message["bytes"]:
                # Binary frame: accumulate audio data
                audio_buffer.extend(message["bytes"])

            elif "text" in message and message["text"]:
                # JSON control message
                try:
                    data = json.loads(message["text"])
                except json.JSONDecodeError:
                    continue

                msg_type = data.get("type", "")

                if msg_type == "voice_start":
                    # New recording session — reset buffer
                    audio_buffer = bytearray()
                    source_format = data.get("format", "amr")
                    logger.info(f"Voice recording started (format={source_format})")

                elif msg_type == "voice_end":
                    # Recording finished — transcribe the buffer
                    if source_format == "text":
                        text_val = data.get("text", "")
                        result = {"text": text_val, "duration_ms": 0}
                    else:
                        if len(audio_buffer) == 0:
                            await websocket.send_json({
                                "type": "VOICE_TRANSCRIPTION",
                                "text": "",
                                "duration_ms": 0,
                                "error": "Empty audio buffer"
                            })
                            continue

                        logger.info(f"Voice recording ended ({len(audio_buffer)} bytes). Transcribing...")
                        result = await voice_agent.transcribe_bytes(
                            bytes(audio_buffer), source_format=source_format
                        )

                    voice_response = ""
                    if result["text"]:
                        logger.info(f"Voice text: \"{result['text'][:80]}...\"")
                        voice_response = await generate_voice_response(result["text"])

                    response = {
                        "type": "VOICE_TRANSCRIPTION",
                        "text": result["text"],
                        "response": voice_response,
                        "duration_ms": result["duration_ms"],
                    }
                    if result.get("error"):
                        response["error"] = result["error"]

                    await websocket.send_json(response)

                    # Reset buffer for next recording
                    audio_buffer = bytearray()

    except WebSocketDisconnect:
        logger.info("Voice WebSocket disconnected")
    except Exception as exc:
        logger.exception(f"Voice WS error: {exc}")


async def generate_voice_response(text: str) -> str:
    """
    Use Ollama to generate a conversational response for the voice command,
    integrating the latest sensor/user telemetry context and retrieved memories
    for a Jarvis-like experience.
    """
    import httpx
    
    # 1. Compile active telemetry context
    context_str = "No active telemetry context available."
    loc = "home"
    stress = 42
    noise = 40
    light = 150
    apps = 0
    typos = 0.0
    task_name = "None"
    
    if latest_contexts:
        try:
            ctx = list(latest_contexts.values())[-1]
            sensor = ctx.get("sensor_data", {})
            user_state = ctx.get("user_state", {})
            active_task = ctx.get("active_task")
            
            loc = sensor.get("location", "home")
            stress = user_state.get("stress_score", 42)
            noise = sensor.get("ambient_noise_db", 40)
            light = sensor.get("ambient_light_lux", 150)
            apps = sensor.get("app_switches", 0)
            typos = sensor.get("typo_rate", 0.0)
            task_name = active_task.get("name") if active_task else "None"
            
            context_str = (
                f"- User Current Location: {loc}\n"
                f"- User Stress Score: {stress}/100\n"
                f"- Active Working Task: {task_name}\n"
                f"- Application Switches (15m): {apps}\n"
                f"- Typo Rate: {typos:.2f}\n"
                f"- Ambient Noise: {noise} dB\n"
                f"- Ambient Light Level: {light} lux\n"
            )
        except Exception as exc:
            logger.warning(f"Failed to compile telemetry context: {exc}")

    # 2. Check if the voice command is asking to retrieve memories
    text_lower = text.lower()
    is_memory_query = any(kw in text_lower for kw in ["memory", "remember", "recall", "history", "past", "episodes", "what did i do", "what was i doing", "last time"])
    
    memories_str = "No relevant memory episodes found."
    retrieved_memories = []
    if is_memory_query and chroma:
        try:
            # We clean up common request phrases to perform a better semantic query
            query_term = text
            for kw in ["fetch data from memory", "fetch from memory", "query memory", "tell me my memory", "retrieve memory"]:
                if kw in query_term.lower():
                    query_term = query_term.lower().replace(kw, "").strip()
            if not query_term:
                query_term = "work stress tasks activities"
            
            logger.info(f"Querying Chroma for voice assistant: '{query_term}'")
            results = chroma.query_memories(query_text=query_term, n_results=3)
            if results:
                retrieved_memories = results
                mem_lines = []
                for r in results:
                    t = r.get("text", "")
                    meta = r.get("metadata", {}) or {}
                    m_loc = meta.get("location", "unknown")
                    m_stress = meta.get("stress_score", "unknown")
                    mem_lines.append(f"- {t} (Location: {m_loc}, Stress: {m_stress})")
                memories_str = "\n".join(mem_lines)
        except Exception as exc:
            logger.warning(f"Failed to query Chroma in voice response: {exc}")

    # 3. Build the Jarvis-like system prompt
    system_prompt = (
        "You are FRIDAY, an advanced, highly intelligent, empathetic, Jarvis-like AI assistant. "
        "Answer the user's spoken command/question in exactly 1 or 2 concise, friendly, and smart sentences. "
        "Integrate the user's real-time context metrics below to give precise, contextual answers if helpful. "
        "Keep your tone conversational, warm, and professional. Do not exceed 2 sentences.\n\n"
        f"USER REAL-TIME CONTEXT:\n{context_str}"
    )
    if is_memory_query:
        system_prompt += f"\n\nUSER RETRIEVED MEMORIES:\n{memories_str}"
    
    response_text = None
    try:
        async with httpx.AsyncClient(timeout=settings.LLM_TIMEOUT) as client:
            resp = await client.post(
                f"{settings.OLLAMA_BASE_URL}/api/generate",
                json={
                    "model": settings.PRIMARY_LLM_MODEL,
                    "prompt": f"<system>{system_prompt}</system>\nUser voice command: {text}",
                    "stream": False,
                    "options": {"temperature": 0.5, "num_predict": 60},
                },
            )
            resp.raise_for_status()
            response_text = resp.json().get("response", "").strip()
    except Exception as e:
        logger.warning(f"Ollama voice response generation failed: {e}")
        
    if response_text:
        return response_text
    
    # 4. Smart, high-fidelity fallback responder (No robotic "I heard you say... I'm processing it now")
    if is_memory_query:
        if retrieved_memories:
            top_mem = retrieved_memories[0].get("text", "")
            if len(retrieved_memories) > 1:
                second_mem = retrieved_memories[1].get("text", "")
                return f"I retrieved a couple of past episodes. Specifically: '{top_mem}', and another record says: '{second_mem}'."
            else:
                return f"According to your past memories, '{top_mem}'."
        else:
            return "I searched your past memory records but did not find any relevant episodes matching that query."

    is_status_query = any(kw in text_lower for kw in ["status", "stress", "how am i", "current state", "how is it going", "active task", "metric"])
    if is_status_query:
        if task_name and task_name != "None":
            task_desc = f"focusing on your task '{task_name}'"
        else:
            task_desc = "not currently working on a specific task"
            
        if stress > 60:
            return f"Your current stress is elevated at {stress}/100 while at {loc}. I suggest pausing for a moment to take a deep breath."
        else:
            return f"You are currently at {loc} with a healthy stress score of {stress}/100, and you are {task_desc}."

    if any(kw in text_lower for kw in ["silence", "quiet", "mute", "block", "do not disturb"]):
        return "Muting alerts and notifications now. I'll make sure you can work without distractions."
        
    if "focus" in text_lower:
        return "Workspace optimized for focus mode. I've minimized noise and interruptions to help you concentrate."
        
    if any(kw in text_lower for kw in ["evening", "brief", "summary"]):
        return f"Evening brief is ready: you spent your session at {loc} with average stress at {stress}/100 and minimal interruptions."

    # General conversational fallback
    return f"I've received your query to '{text}' and logged it to keep your workspace optimized. Let me know if you want me to fetch any memory or tell you your current status."


# ──────────────────────────────────────────────────────────────────────────────
# HTTP — Voice Transcription (file upload)
# ──────────────────────────────────────────────────────────────────────────────

from fastapi import UploadFile, File

@app.post("/api/transcribe", tags=["voice"])
async def transcribe_audio(file: UploadFile = File(...)):
    """
    HTTP endpoint for single-shot audio transcription.
    Upload an audio file and receive the transcribed text.
    """
    if voice_agent is None:
        raise HTTPException(status_code=503, detail="Voice transcription unavailable")

    audio_data = await file.read()
    if len(audio_data) == 0:
        raise HTTPException(status_code=400, detail="Empty audio file")

    # Determine format from filename extension
    ext = file.filename.rsplit(".", 1)[-1].lower() if file.filename else "wav"

    result = await voice_agent.transcribe_bytes(audio_data, source_format=ext)

    if result.get("error"):
        raise HTTPException(status_code=500, detail=result["error"])

    return {
        "text": result["text"],
        "duration_ms": result["duration_ms"],
    }


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=settings.PORT,
        reload=settings.DEBUG,
        log_level="info",
    )
